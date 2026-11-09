# User guide

The [README](../../README.md) is the short version: a client in a few lines. These pages are the detail behind it, for
everything the transport does: configuration, authentication, rate limits and errors. They apply to every library built
on `github-client`, such as [github-rest-client](https://github.com/piotr-janczukowicz/github-rest-client) and
[github-graphql-client](https://github.com/piotr-janczukowicz/github-graphql-client). Read them in order, or jump to the
one you need.

| Chapter | What it covers |
|---|---|
| 1. [Configuration](configuration.md) | Every client option with its default and its limits on github.com, Enterprise Cloud and Enterprise Server, and examples |
| 2. [Authentication in depth](authentication.md) | Rotating tokens, GitHub App installations and the token lifecycle, authenticating as the app, client credentials, SAML single sign-on, security |
| 3. [Rate limiting in depth](rate-limiting.md) | Primary and secondary limits, buckets, credentials, long waits, the conditional cache, polling, concurrency and pacing, monitoring |
| 4. [Exception handling](exception-handling.md) | Every exception that reaches your code, what the client handles for you and what you should handle |

The examples send plain calls with `GitHubCaller`. A library built on the client gives you typed methods for the same
calls.
