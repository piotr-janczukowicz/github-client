# Rate limiting in depth

Previous: [Authentication in depth](authentication.md) · [Guide index](README.md) · Next: [Exception handling](exception-handling.md)

This page is part of the [user guide](README.md). The [README](../../README.md) has the short version.

Clients created with `GitHubClients` follow GitHub's [REST API rate-limit
rules](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api). You can tune them through
`GitHubClientConfiguration` and `RetryConfiguration`, but you cannot switch them off.

## Configuring

```java
GitHubClient client = GitHubClients.create(
        AuthenticationProviders.bearer(System.getenv("GITHUB_TOKEN")),
        "my-app/1.0",
        GitHubClientConfiguration.builder()
                .retry(RetryConfiguration.builder()
                        .maxRateLimitWait(Duration.ofMinutes(10))
                        .build())
                .build());
```

## Primary limits

When GitHub answers 403 or 429 with `x-ratelimit-remaining: 0`, the client waits until `x-ratelimit-reset` and retries.
It measures time against the server's `Date` header and adds random jitter (`rateLimitJitter`, 10 seconds by default),
so threads released by the same limit do not resume together.

Limits are higher for resources owned by GitHub Enterprise Cloud: 15,000 per hour instead of 5,000 for GitHub Apps and
OAuth apps owned by an Enterprise Cloud organization, installation tokens, and `GITHUB_TOKEN` on Enterprise Cloud account
resources. The client reads the actual limit from the `x-ratelimit-*` headers, so no configuration is needed.

## Used-up quota

A successful response (2xx or 3xx, a 304 included) with `x-ratelimit-remaining: 0` and a reset in the future blocks its
bucket until that reset, as a rejection would. The response itself is returned unchanged.

## Secondary limits

On a secondary limit the client waits for `retry-after`. If that header is missing, it waits until the reset when
`remaining` is 0; otherwise it waits 60 seconds (`minRateLimitWait`) and doubles the wait on each further hit, up to
5 minutes (`maxRateLimitWait`). The escalation is kept per credential and restarts 5 minutes after the last wait ended.
After 4 rate-limited attempts (`maxRateLimitAttempts`) the client throws `GitHubRateLimitException`.

## Buckets

A limit applies to one quota bucket: the `x-ratelimit-resource` GitHub names, or the bucket of the request path
(`core`, `search`, `code_search`, `graphql`, and others). A used-up `search` quota does not hold back `core` requests.
A secondary limit applies to every bucket.

## Long waits fail fast

If GitHub asks for more than `maxRateLimitWait`, the client throws `GitHubRateLimitException` at once instead of
blocking your thread. `retryAt()` says when to try again.

- A block lasts at most `maxRateLimitBlock` (one hour).
- Until it ends, later calls with the same credential to the same bucket fail locally, with `isClientSideBlock()` true,
  and never reach GitHub.

```java
try {
    return caller.fetch(HttpCall.get(client.baseUri().resolve("user")), Me.class);
} catch (GitHubRateLimitException e) {
    e.retryAt().ifPresent(at -> log.warn("GitHub rate limit, retry after {}", at));
    throw e;
}
```

## Credentials

Blocks, escalations and cached responses belong to one credential and are never shared between credentials. A token is
identified by a SHA-256 digest of its value. An installation is identified by its app and installation IDs, so a token
that rotates during a block does not release it. Client credentials are identified by the client ID.

## Unauthenticated requests

`AuthenticationProviders.none()` sends no `Authorization` header. GitHub allows 60 requests per hour per IP address. This
client approximates that limit with one `anonymous` bucket per origin, and it cannot see other processes on the same IP
address. Authenticated and unauthenticated requests never share a block or a cached response.

## Conditional requests

The client makes repeated `GET` requests conditional on its own:

- A 200 response with an `ETag` or `Last-Modified` header (and no `no-store`) is kept in memory.
- The next `GET` of the same URL is sent with `If-None-Match` or `If-Modified-Since`.
- A 304 returns the kept response, and its headers refresh the rate-limit state.
- Writes are never cached, and the cache cannot be switched off.

Defaults: 64 entries, 1 MiB per body and 4 MiB in all. Each limit is at least 1.

```java
GitHubClientConfiguration.builder()
        .conditionalCacheMaxEntries(256)
        .conditionalCacheMaxBodyBytes(2 * 1024 * 1024)
        .conditionalCacheMaxBytes(16L * 1024 * 1024)
        .build();
```

## Polling

If you poll a resource, wait at least the interval the server asks for. `GitHubPollInterval.of(result)` reads
`x-poll-interval` of a `caller.fetchResult(...)` result as a `Duration`, and is empty when the header is missing, negative
or not a whole number of seconds.

## Concurrency and pacing

By default one request runs at a time, as GitHub's best practices recommend (`maxConcurrentRequests`, from 1 to 100).
Raising it lets more requests run at once, but increases the risk of secondary limits on github.com and Enterprise
Cloud; keep the default there. A GitHub Enterprise Server client can raise it to what the instance can take. Mutations (POST, PATCH, PUT and
DELETE) start at least `mutationInterval` apart, which defaults to 1 second and cannot be shorter, except for a
GitHub Enterprise Server client (`GitHubFlavor.GHES`): a server has its own limits, so it may use a shorter interval,
and `Duration.ZERO` switches the pacing off.

```java
GitHubClientConfiguration.builder()
        .transport(HttpTransportConfiguration.builder().maxConcurrentRequests(1).build())
        .mutationInterval(Duration.ofSeconds(2))
        .build();
```

## Monitoring

`client.lastRateLimit("core")` and `client.lastRateLimits()` return the latest quota seen per resource. Use them for
monitoring and budgeting, not to pace calls: the client already waits for and blocks on rate limits. `GET /rate_limit`
does not count against the primary limit, so you can also read the quotas from GitHub with a plain call.

```java
client.lastRateLimit("core").ifPresent(limit ->
        log.info("core: {} of {} left, resets at {}", limit.remaining(), limit.limit(), limit.resetAt()));
```

---

Previous: [Authentication in depth](authentication.md) · [Guide index](README.md) · Next: [Exception handling](exception-handling.md)
