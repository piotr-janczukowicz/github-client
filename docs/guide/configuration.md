# Configuration

Next: [Authentication in depth](authentication.md) · [Guide index](README.md)

This page is part of the [user guide](README.md). The [README](../../README.md#configuration) has the short version.

`GitHubClients.create(auth, userAgent)` uses the defaults, and the defaults follow GitHub's own advice for github.com. On
**github.com and GitHub Enterprise Cloud** keep them and set only what your case needs, such as the base URI of a
data-residency tenant. Raising the concurrency or shortening the pause between writes makes GitHub's secondary limits
likelier, so the client does not let you go below the floors in the table.

A **GitHub Enterprise Server** instance is yours: its administrator decides whether and how rate limits apply, and what
the hardware can take. Tune the client to it as you like, within the same safety rules, and set the three things it
always needs: the base URI, the `GHES` flavor and the release. A library built on the client, such as
[github-rest-client](https://github.com/piotr-janczukowicz/github-rest-client/blob/main/docs/guide/flavors.md), uses the
flavor and the release to reject a call that the configured product lacks before it is sent.

| Option | Default | github.com and Enterprise Cloud | Enterprise Server |
|---|---|---|---|
| `maxConcurrentRequests` | 1 | Keep 1; more raises the risk of secondary limits | 1 to 100, as the instance can take |
| `mutationInterval` | 1 s | At least 1 s | Any value from `Duration.ZERO` (no pacing) up |
| `retry` (waits, attempts, backoff) | Waits out rate limits, retries transient failures | Tune the waits and attempts; rate-limit handling cannot be switched off | The same |
| Conditional cache limits | 64 entries, 1 MiB per body, 4 MiB in all | Rarely worth changing | Size to your workload; the cache cannot be switched off |
| Timeouts, proxy and TLS of the transport | OkHttp defaults of the library | Set what your network needs | The same |
| `allowInsecureHttp` | `false` | Not needed | `true` for a plain `http` base URI on a host other than loopback |
| Base URI | `https://api.github.com/` | Only for `https://api.SUBDOMAIN.ghe.com/` | Required: `https://HOST/api/v3/` |
| `flavor` and `ghesVersion` | Unset: nothing is checked | Set `GHEC` for Enterprise Cloud, which shares the base URI with github.com | Required: `GHES` with the release 3.21 or 3.22 |

```java
// github.com: defaults
GitHubClient cloud = GitHubClients.create(AuthenticationProviders.bearer(token), "my-app/1.0");

// Enterprise Server: tuned to the instance
GitHubClient server = GitHubClients.create(
        AuthenticationProviders.bearer(token),
        "my-app/1.0",
        GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(URI.create("https://HOST/api/v3/")))
                .flavor(GitHubFlavor.GHES)
                .ghesVersion(new GhesVersion(3, 22))
                .transport(HttpTransportConfiguration.builder().maxConcurrentRequests(8).build())
                .mutationInterval(Duration.ZERO)
                .build());
```

---

Next: [Authentication in depth](authentication.md) · [Guide index](README.md)
