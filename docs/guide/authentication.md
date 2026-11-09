# Authentication in depth

Previous: [Configuration](configuration.md) · [Guide index](README.md) · Next: [Rate limiting in depth](rate-limiting.md)

This page is the detail behind the [Authentication](../../README.md#authentication) section of the README.

Every client sends one `Authorization` header, chosen by its `AuthenticationProvider`. Build a client with
`GitHubClients.create(auth, userAgent)`; for another auth, create another client. GitHub's rules are in its
[authentication documentation](https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api).

## Personal access tokens

`AuthenticationProviders.bearer(token)` sends `Bearer <token>`. Prefer fine-grained personal access tokens.

```java
GitHubClient client = GitHubClients.create(AuthenticationProviders.bearer(System.getenv("GITHUB_TOKEN")), "my-app/1.0");
```

## Rotating tokens

`AuthenticationProviders.bearer(() -> currentToken())` reads a token that changes over time, for example an OAuth user
token that you refresh yourself. A 401 is not retried with a supplied token.

To retry after a refresh, pass a second function. It receives the rejected token and returns `true` once a different
token is available, and the client then sends the request once more.

```java
AuthenticationProviders.bearer(() -> currentToken(), token -> refreshed(token));
```

## GitHub App installations

`GitHubInstallationAuthenticationProviders.create(userAgent, clientConfiguration, configuration)` makes the auth of one
installation. Its token requests go through a client of their own, built from the same `userAgent` and
`clientConfiguration` you give `GitHubClients.create`, so both reach the same host with the same proxy and trust.
You create the auth, so you close it as well: `installationClient.close()` does not close it.

```java
GitHubInstallationAuthenticationProvider auth = GitHubInstallationAuthenticationProviders.create(
        userAgent, clientConfiguration, GitHubInstallationAuthConfiguration.builder()
        .appId("Iv1.0123456789abcdef")          // client ID (recommended) or numeric app ID
        .privateKeyPem(pem)                      // PKCS#1 or PKCS#8, unencrypted
        .installationId(12345678L)
        .repositories(List.of("hello-world"))    // optional: bare repository names
        .permissions(Map.of("contents", "read")) // optional: narrower than the app's permissions
        .build());
GitHubClient installationClient = GitHubClients.create(auth, userAgent, clientConfiguration);
```

## Installation token lifecycle

The library signs a JWT for the app (RS256, `iat` 60 seconds in the past, expiring after 9 minutes) and exchanges it for
an installation access token, which it caches.

- **Renewal:** the token is renewed 5 minutes before it expires (`refreshMargin`), or half-way through its life if that
  is sooner. Only one thread renews; the others keep using the token they have.
- **Rejection:** on 401 the client fetches a new token and sends the request once more, unless the token was fetched less
  than 10 seconds earlier (`minRenewInterval`).
- **Back-off:** failed token requests back off from 1 to 30 seconds (`failureBackoff`). A call with no unexpired token
  during the back-off fails with `GitHubInstallationTokenUnavailableException`; its `retryAt()` says when to retry.

`close()` closes the token client of the auth and its transport; the client that uses the auth stays usable. The
overloads without a configuration build the default configuration from the app ID, key and installation ID. Their
`Clock` overloads time the JWT and the token expiry, which is useful in tests.

## Authenticating as the app

`GitHubAppAuthenticationProviders.app(appId, privateKeyPem)` signs a fresh JWT for each request. Use it for app-level
endpoints, such as `GET /app` and `GET /app/installations`, and to find the installation ID for
`GitHubInstallationAuthenticationProviders`. A 401 is not retried with a new JWT, because signing again cannot fix a
rejected key.

```java
record App(String name) {}

try (GitHubClient appClient = GitHubClients.create(GitHubAppAuthenticationProviders.app(appId, pem), userAgent)) {
    App app = GitHubCaller.of(appClient).fetch(HttpCall.get(appClient.baseUri().resolve("app")), App.class);
}
```

## Client credentials

Some endpoints take the client ID and client secret of the app as HTTP Basic credentials, such as
`/applications/{client_id}/token`, which checks a token. `AuthenticationProviders.basic(clientId, clientSecret)` sends
`Basic` with the Base64 of `clientId:clientSecret`. GitHub does not accept a username and password, and this library does
not offer them. A 401 is not retried with these credentials.

```java
GitHubClient oauthClient = GitHubClients.create(AuthenticationProviders.basic(clientId, clientSecret), userAgent);
```

## Unauthenticated access

`AuthenticationProviders.none()` sends no `Authorization` header. Use it only for public data: GitHub allows 60
unauthenticated requests per hour per IP address, and hides many endpoints from unauthenticated callers. See
[Rate limiting](rate-limiting.md) for how the client counts them.

## Errors

- `GitHubUnauthorizedException` (401): the credentials are invalid, expired or revoked.
- `GitHubForbiddenException` (403): the token lacks the permission the request needs, or GitHub is temporarily
  rejecting every authentication attempt for that user, valid ones included, after several invalid attempts in a short
  period. In the second case stop retrying with the same credentials and wait. A 403 that reports a rate limit is
  `GitHubRateLimitException`.
- `GitHubNotFoundException` (404): for private resources GitHub may answer 404 instead of 403.

```java
try {
    return caller.fetch(HttpCall.get(client.baseUri().resolve("user")), Me.class);
} catch (GitHubUnauthorizedException e) {
    throw new IllegalStateException("Token expired or revoked, re-authenticate", e);
}
```

## SAML single sign-on

When a token is not authorized for an organization that uses SAML SSO, GitHub's 403 carries a link.
`GitHubForbiddenException.ssoUrl()` returns it. A list response may leave out items of such organizations without an
error, and then the `X-GitHub-SSO: partial-results` header names them. For a response read with `client.execute(request)`,
`GitHubSsoHeaders.partialResultOrganizations(response.headers())` returns their IDs. The client logs that once at DEBUG
level.

## Security

- The auth types do not print their secrets. `toString()` shows only the app ID, the client ID or the installation ID,
  and `HttpHeaders.toString()` prints `Authorization` as `***`.
- A plain `http` base URI is refused on a host other than loopback, unless `GitHubClientConfiguration` allows it with
  `allowInsecureHttp(true)`.
- A redirect that leaves the API origin is followed only for GET and HEAD, and carries only `Accept` and `User-Agent`, so
  no credentials go to the other origin.

---

Previous: [Configuration](configuration.md) · [Guide index](README.md) · Next: [Rate limiting in depth](rate-limiting.md)
