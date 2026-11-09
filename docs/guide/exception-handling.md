# Exception handling

Previous: [Rate limiting in depth](rate-limiting.md) · [Guide index](README.md)

This page is part of the [user guide](README.md). The [README](../../README.md) has the short version.

## What reaches your code

| Exception | When | Type |
|---|---|---|
| `GitHubUnauthorizedException` | 401: the token is missing, expired, revoked or malformed | unchecked |
| `GitHubForbiddenException` | 403 that is not a rate limit: missing permission, blocked access, policy, SAML SSO (`ssoUrl()`) | unchecked |
| `GitHubNotFoundException` | 404: the resource is missing, or private and not visible to this credential | unchecked |
| `GitHubValidationException` | 422: the payload was rejected; `errors()` lists the fields | unchecked |
| `GitHubRateLimitException` | a rate limit the client did not wait out (see [Long waits fail fast](rate-limiting.md#long-waits-fail-fast)) | unchecked |
| `GitHubApiException` | any other unsuccessful status: 409, 410, 5xx after the retries, or 304 you did not ask for | unchecked |
| `IOException` | the transport failed: connection, timeout, TLS, a body above the size limit (`ResponseTooLargeException`) | checked |
| `UncheckedIOException` | the same transport failures while you read `stream()` or `pages()`, which cannot throw a checked exception; the cause is the `IOException` (`all()` and `forEachPage(...)` throw it directly) | unchecked |
| `JsonDecodeException` | the response body could not be read as the expected model | unchecked |
| `JsonEncodeException` | a payload you passed could not be written as JSON | unchecked |
| `HttpEmptyResponseException` | a successful response without a body where a value was expected (202, 204) | unchecked |
| `GitHubPaginationException` | the next page cannot be followed, a page repeats, or `maxPages` is reached (`GitHubPaginationLimitExceededException`) | unchecked |
| `GitHubInstallationTokenUnavailableException` | an installation token fetch failed earlier and the provider is in its backoff; `retryAt()` | unchecked |
| `HttpOriginViolationException` | a request target leaves the API origin, such as an absolute URL on another host; it is an `IllegalArgumentException` | unchecked |
| `IllegalArgumentException`, `NullPointerException` | an argument is blank, null or outside its limits | unchecked |

A library built on the client may add its own exceptions. For example, a call that the configured flavor lacks fails with
`UnsupportedOperationException` in [github-rest-client](https://github.com/piotr-janczukowicz/github-rest-client/blob/main/docs/guide/flavors.md),
and a failed GraphQL query is a `GitHubGraphQLException` in
[github-graphql-client](https://github.com/piotr-janczukowicz/github-graphql-client).

Every `GitHubApiException` carries `statusCode()`, `documentationUrl()`, `requestId()` (quote it to GitHub support),
`rawBody()`, `headers()`, `method()` and `uri()`.

## What the client handles for you

- **Rate limits** are waited out when the wait is at most `maxRateLimitWait`, with the attempts and the coordination across
  calls described in [Rate limiting](rate-limiting.md). You see `GitHubRateLimitException` only for a longer wait or after the
  attempts are used up.
- **Transient failures** are retried: `502`, `503` and `504`, and I/O failures. By default only for `GET`, `HEAD` and
  `OPTIONS`; a failed connection attempt is retried for every method. A `POST`, `PATCH`, `PUT` or `DELETE` that fails
  after the request was sent is not repeated, because the change may have been applied.
- **A 304** is handled by the conditional cache; you do not see it.
- **Token refresh** is done by the authentication you chose; see [Rotating tokens](authentication.md#rotating-tokens).

## What you should handle

- **Not found.** `GitHubNotFoundException` does not prove the resource is missing: GitHub answers 404 for a private resource
  too. For a lookup of one resource by a key, use `caller.fetchOrEmpty(...)`, which turns a 404 into an empty result;
  other 404s still throw.
- **Retry yourself only what is safe.** After an `IOException` on a mutation, read the state before repeating the call.
  After `GitHubRateLimitException`, wait until `retryAt()`. After `GitHubValidationException`, fix the payload; repeating
  it fails the same way.
- **Stop on credentials.** On `GitHubUnauthorizedException`, re-authenticate; do not loop with the same token. On a
  `GitHubForbiddenException` with `ssoUrl()`, the user must authorize the token for the organization.
- **Do not catch programming errors.** `IllegalArgumentException` and `NullPointerException` mean the call is wrong, not
  that GitHub failed.
- **Logging.** Exception messages contain no query string and no token, and `Authorization` is printed as `***`. The cause
  of a `JsonDecodeException` may quote a value from the response body, so do not log it where the body is secret.

```java
try {
    return caller.fetchOrEmpty(HttpCall.get(client.baseUri().resolve("repos/octocat/Hello-World")), Repo.class); // a 404 is empty
} catch (GitHubRateLimitException e) {
    schedule(e.retryAt().orElse(Instant.now().plusSeconds(60)));
    throw e;
} catch (GitHubValidationException e) {
    e.errors().forEach(error -> log.warn("{} {}: {}", error.field(), error.code(), error.message()));
    throw e;
} catch (GitHubApiException e) {
    log.error("GitHub answered {} (request {})", e.statusCode(), e.requestId().orElse("unknown"), e);
    throw e;
} catch (IOException e) {
    throw new UncheckedIOException("GitHub is unreachable", e);
}
```

Catch the specific types first: `GitHubApiException` is their parent.

---

Previous: [Rate limiting in depth](rate-limiting.md) · [Guide index](README.md)
