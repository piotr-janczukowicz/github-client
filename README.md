# github-client

Java 21 transport for the GitHub APIs: an authenticated, retrying, rate-limit aware HTTP client for github.com, GitHub
Enterprise Cloud and GitHub Enterprise Server, with GitHub's error mapping and pagination built in.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.piotr-janczukowicz/github-client)](https://central.sonatype.com/artifact/io.github.piotr-janczukowicz/github-client)
![Java 21](https://img.shields.io/badge/Java-21-orange.svg)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Build](https://github.com/piotr-janczukowicz/github-client/actions/workflows/main.yml/badge.svg)](https://github.com/piotr-janczukowicz/github-client/actions/workflows/main.yml)
[![Codacy Coverage](https://app.codacy.com/project/badge/Coverage/d6fd9e7bad624917b41ca4b1d0dcfd1a)](https://app.codacy.com/gh/piotr-janczukowicz/github-client/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_coverage)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/d6fd9e7bad624917b41ca4b1d0dcfd1a)](https://app.codacy.com/gh/piotr-janczukowicz/github-client/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

## Why this library

- **Authentication.** Bearer tokens, basic authentication, GitHub App JWTs and GitHub App installation tokens, which are
  cached and renewed before they expire.
- **Retries and rate limits.** Primary and secondary rate limits are waited out, and the latest quota per resource is
  available from the client. A failure to connect is retried; a `POST` is not repeated after the request may have reached
  GitHub.
- **Errors.** A failed response becomes a typed exception (`GitHubNotFoundException`, `GitHubRateLimitException`,
  `GitHubValidationException`, ...) that carries GitHub's message and validation errors.
- **Pagination.** `Link` headers, page numbers, cursors and start indexes are followed lazily as a `GitHubPage`, with a
  page limit, loop detection and a guard against a `Link` that leaves the API.
- **GitHub Enterprise Server.** The API origin is guarded, the management console has its own caller, and a call can be
  rejected before it is sent when the configured flavor or release lacks the operation.
- **JSON.** A codec for GitHub's JSON (snake_case, unknown properties ignored, immutable collections). A library on the
  path can add the Jackson modules of its own models by providing a `GitHubJsonModule` through the `ServiceLoader`.

## Install

**Requirements:** Java 21. The library is a Java module, `io.github.piotr_janczukowicz.githubclient`, and also works on
the class path. It is published on
[Maven Central](https://central.sonatype.com/artifact/io.github.piotr-janczukowicz/github-client):

```xml
<dependency>
    <groupId>io.github.piotr-janczukowicz</groupId>
    <artifactId>github-client</artifactId>
    <version>LATEST_VERSION</version>
</dependency>
```

Replace `LATEST_VERSION` with the version in the Maven Central badge at the top of this page, or with the newest entry on
the [releases page](https://github.com/piotr-janczukowicz/github-client/releases).

## Quick start

```java
record Me(String login) {}

try (GitHubClient client = GitHubClients.create(AuthenticationProviders.bearer(token), "my-app/1.0")) {
    GitHubCaller caller = GitHubCaller.of(client);

    Me me = caller.fetch(HttpCall.get(client.baseUri().resolve("user")), Me.class);
}
```

`GitHubCaller` sends a call and decodes the answer: `fetch` returns the decoded body, `fetchOrEmpty` returns an empty
`Optional` on a 404, and `list` returns a `GitHubPage`. A library that models GitHub's operations builds its calls and
hands them to the caller.

## Authentication

```java
// a token
AuthenticationProviders.bearer(token);

// a GitHub App: a JWT signed with the app's private key
GitHubAppAuthenticationProviders.app(appId, privateKeyPem);
```

A GitHub App installation token is fetched, cached and renewed by `GitHubInstallationAuthenticationProviders`.

## Configuration

`GitHubClients.create(authentication, userAgent, configuration)` takes a `GitHubClientConfiguration`, which wraps the HTTP
settings (base URI, timeouts, retries) and adds the pagination limits (`perPage` and `maxPages`) and, for GitHub
Enterprise Server, the flavor and release. Without a configuration the client talks to `https://api.github.com/`.

## User guide

The [user guide](docs/guide/README.md) has a chapter for each topic below.

| Topic | In short |
|---|---|
| [Configuration](docs/guide/configuration.md) | Every option, its default and its limits on github.com, Enterprise Cloud and Enterprise Server |
| [Authentication in depth](docs/guide/authentication.md) | Rotating tokens, App installations and token renewal, authenticating as the app, SAML single sign-on, security notes |
| [Rate limiting](docs/guide/rate-limiting.md) | Waited out for you; a wait over `maxRateLimitWait` throws `GitHubRateLimitException` with `retryAt()` |
| [Exception handling](docs/guide/exception-handling.md) | HTTP errors are unchecked `GitHubApiException` subclasses; what the client handles and what you should |

## Disclaimer

This library is the transport of the other libraries in this family, and it is mainly tested against mock servers. Only
the smoke tests of the libraries built on it have been run on the live GitHub environment, covering particular feature
sets. This library is new and not yet proven in production.

**Verify the behavior of any call you depend on before using it in production.**

## License

[MIT](LICENSE)
