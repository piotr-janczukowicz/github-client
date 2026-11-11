package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.internal.ClientStacks;
import io.github.piotr_janczukowicz.github.client.internal.DefaultGitHubClient;
import io.github.piotr_janczukowicz.http.HttpUserAgents;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.transport.ConditionalCachingHttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport;

/**
 * Factory for the default {@link GitHubClient}. Every client it creates owns its transport, which is closed with the
 * client, and sends through the same stack, outermost first:
 *
 * <ol>
 *   <li>conditional cache: keeps the 200 responses to GET requests that carry an {@code ETag} or a
 *       {@code Last-Modified} header, within {@link GitHubClientConfiguration#conditionalCacheMaxEntries()} responses
 *       and {@link GitHubClientConfiguration#conditionalCacheMaxBytes()} bytes of bodies, and answers a repeated GET
 *       from memory when GitHub replies 304 (see {@link ConditionalCachingHttpTransport}); it is outermost, so that
 *       every 304 still passes the layers below, which record its rate-limit headers and may hold back the next call;
 *   <li>retry: retries failed attempts, and waits out GitHub's primary and secondary rate limits as GitHub's
 *       documentation requires (see {@link GitHubClient}); it also holds back the other calls of the same credential
 *       while a limit lasts, including after a successful response that used up the quota;
 *   <li>mutation pacing: at least {@link GitHubClientConfiguration#mutationInterval()} (one second by default) between
 *       the starts of POST, PATCH, PUT and DELETE requests, so that a burst of writes does not trip GitHub's
 *       content-creation limits; GET requests are never delayed; a GHES client may set the interval to zero, which
 *       leaves this layer out;
 *   <li>concurrency limit: at most {@link HttpTransportConfiguration#maxConcurrentRequests()} requests in flight,
 *       GitHub's limit on concurrent requests; the default is 1, which sends strictly one request at a time, as
 *       GitHub's best practices recommend, and a higher value raises the risk of secondary rate limits;
 *   <li>OkHttp transport.
 * </ol>
 *
 * <p>Pacing sits above the concurrency limit, so a pause holds no slot, and below retry, so retries are paced too. The
 * stack cannot be switched off: {@link GitHubClientConfiguration} rejects a configuration that would disable rate-limit
 * handling. The client returned here owns and closes the stack. A client shares nothing with another client, the token
 * clients of installation auths included.
 *
 * <p>Responses not read with {@code stream(...)} are buffered in memory up to
 * {@link HttpTransportConfiguration#maxResponseBytes()} (64 MiB by default).
 *
 * <p><strong>Scope.</strong> The waits and blocks of the retry layer and the cached responses are kept per stack and
 * per credential: a limit or a cached copy of one credential never applies to another. An installation is one
 * credential, however often its token rotates
 * ({@link io.github.piotr_janczukowicz.http.client.authentication.Authentication#identity()}). Requests without
 * credentials ({@link io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders#none()}) share
 * one anonymous bucket per origin and stack; GitHub counts that limit (60 requests per hour) per IP address, which the
 * client only approximates. Separate root clients share none of it. A client is thread-safe and meant to be shared, so
 * create one per credential and {@link GitHubClient#close() close} it when done: an unclosed client holds its pooled
 * sockets until the connection keep-alive expires (5 minutes by default). See the <a
 * href="https://github.com/piotr-janczukowicz/github-rest-client/blob/main/docs/guide/rate-limiting.md">Rate
 * limiting</a> section of the README for what the client does for you and what you can tune.
 */
public final class GitHubClients {

    private static final String CONFIGURATION_PARAMETER = "configuration";

    private GitHubClients() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Client for the GitHub.com REST API with the default configuration: {@link GitHubClientConfiguration#defaults()}.
     *
     * @param auth source of the bearer token, read on every request
     * @param userAgent application name sent as {@code User-Agent}; GitHub requires one, and it must be visible ASCII
     * @return client that owns its transport; close it when done
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code userAgent} is blank or has characters other than visible ASCII
     */
    public static GitHubClient create(final AuthenticationProvider auth, final String userAgent) {
        return create(auth, userAgent, GitHubClientConfiguration.defaults());
    }

    /**
     * Client for {@code configuration} with the other default configuration.
     *
     * @param auth source of the bearer token, read on every request
     * @param userAgent application name sent as {@code User-Agent}; GitHub requires one, and it must be visible ASCII
     * @param configuration base URI, extra default headers and pagination
     * @return client that owns its transport; close it when done
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code userAgent} is blank or has characters other than visible ASCII
     */
    public static GitHubClient create(
            final AuthenticationProvider auth, final String userAgent, final HttpClientConfiguration configuration) {
        return create(
                auth,
                userAgent,
                GitHubClientConfiguration.builder()
                        .configuration(requireNonNull(configuration, CONFIGURATION_PARAMETER))
                        .build());
    }

    /**
     * Client with the given configuration. Every argument is checked before the transport is created, so a bad one
     * leaves nothing to close.
     *
     * @param auth source of the bearer token, read on every request
     * @param userAgent application name sent as {@code User-Agent}; GitHub requires one, and it must be visible ASCII
     * @param configuration base URI, transport, concurrency, retry, mutation interval and listeners of the client
     * @return client that owns its transport; close it when done
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code userAgent} is blank or has characters other than visible ASCII, or the
     *     default headers of the configuration set {@code User-Agent} or {@code X-GitHub-Api-Version}
     */
    public static GitHubClient create(
            final AuthenticationProvider auth, final String userAgent, final GitHubClientConfiguration configuration) {
        requireNonNull(auth, "auth");
        HttpUserAgents.requireValid(userAgent);
        requireNonNull(configuration, CONFIGURATION_PARAMETER);
        final HttpTransport okHttp = HttpTransports.okHttp(configuration.transport());
        return ClientStacks.assemble(
                        okHttp,
                        auth,
                        userAgent,
                        configuration,
                        configuration.mutationInterval(),
                        RetryingHttpTransport::new)
                .build();
    }

    /**
     * Client for the few requests that obtain a token, such as the installation access token request of a GitHub App.
     * Its stack is shorter than the one of {@link #create(AuthenticationProvider, String, GitHubClientConfiguration)}:
     * the OkHttp transport from {@link GitHubClientConfiguration#transport()} and the retry layer on it, so that rate
     * limits and transient failures of the token endpoint are waited out. It has no conditional cache, mutation pacing
     * or concurrency limit, which a handful of POST requests does not need. It uses the base URI, default headers,
     * transport, retry, {@code allowInsecureHttp}, listener and call observer of {@code configuration}, so that it
     * reaches the same host, through the same proxy and with the same trust, as a client made from the same
     * configuration. The client owns its transport; close it when done.
     *
     * @param auth source of the credentials of the token requests, read on every request
     * @param userAgent application name sent as {@code User-Agent}; GitHub requires one, and it must be visible ASCII
     * @param configuration base URI, transport, retry and listeners of the client
     * @return client that owns its transport; close it when done
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code userAgent} is blank or has characters other than visible ASCII, or the
     *     default headers of the configuration set {@code User-Agent} or {@code X-GitHub-Api-Version}
     */
    public static GitHubClient createTokenClient(
            final AuthenticationProvider auth, final String userAgent, final GitHubClientConfiguration configuration) {
        requireNonNull(auth, "auth");
        HttpUserAgents.requireValid(userAgent);
        requireNonNull(configuration, CONFIGURATION_PARAMETER);
        final HttpTransport base = HttpTransports.okHttp(configuration.transport());
        try {
            return ClientStacks.configured(
                            DefaultGitHubClient.builder(
                                    ClientStacks.retrying(base, configuration.retry()),
                                    configuration.configuration(),
                                    auth),
                            userAgent,
                            configuration)
                    .build();
        } catch (final RuntimeException rejected) {
            // The builder refused an argument, so no client owns the stack yet, and it is released here.
            ClientStacks.logRefused(rejected);
            base.close();
            throw rejected;
        }
    }
}
