package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitDetector;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.client.HttpCallObserver;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.transport.ConcurrencyLimitingHttpTransport;
import io.github.piotr_janczukowicz.http.transport.ConditionalCachingHttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.http.transport.MutationPacingHttpTransport;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import java.time.Duration;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(GitHubClients.class);

    private static final String CONFIGURATION_PARAMETER = "configuration";

    // The representation of a GitHub response depends on Accept and on the REST API version, so both key the cache.
    private static final Set<String> CONDITIONAL_CACHE_VARYING_HEADERS =
            Set.of(HttpHeaderNames.ACCEPT, GitHubHeaders.API_VERSION);

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
        DefaultGitHubClient.requireValidUserAgent(userAgent);
        requireNonNull(configuration, CONFIGURATION_PARAMETER);
        final HttpTransport okHttp = HttpTransports.okHttp(configuration.transport());
        return assemble(
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
        DefaultGitHubClient.requireValidUserAgent(userAgent);
        requireNonNull(configuration, CONFIGURATION_PARAMETER);
        final HttpTransport base = HttpTransports.okHttp(configuration.transport());
        try {
            return configured(
                            DefaultGitHubClient.builder(
                                    retrying(base, configuration.retry()), configuration.configuration(), auth),
                            userAgent,
                            configuration)
                    .build();
        } catch (final RuntimeException rejected) {
            // The builder refused an argument, so no client owns the stack yet, and it is released here.
            logRefused(rejected);
            base.close();
            throw rejected;
        }
    }

    /**
     * Wraps {@code delegate} in the retry layer of the GitHub stack, with GitHub's rate-limit detector.
     *
     * @param delegate transport to retry
     * @param retry retry settings
     * @return retrying transport that closes {@code delegate} when it is closed
     * @throws NullPointerException if an argument is {@code null}
     */
    static HttpTransport retrying(final HttpTransport delegate, final RetryConfiguration retry) {
        return new RetryingHttpTransport(requireNonNull(delegate, "delegate"), githubRetry(retry));
    }

    /**
     * Builds the GitHub stack over {@code base} and returns a builder of the client on it. The builder's client owns
     * the stack: if the client cannot be built, {@code build()} of the returned builder closes the stack. If a
     * decorator or the builder refuses an argument, {@code base} is closed here and the rejection is rethrown.
     * Package-private so that tests can replace the mutation interval and the retry layer, which production code takes
     * from {@code configuration} and {@link RetryingHttpTransport}.
     *
     * @param base transport at the bottom of the stack, closed with the client or on a refusal
     * @param auth source of the bearer token
     * @param userAgent application name sent as {@code User-Agent}
     * @param configuration concurrency, retry and listener configuration
     * @param mutationInterval least time between the starts of two mutations; zero (GHES only, see
     *     {@link GitHubClientConfiguration}) leaves the mutations unpaced
     * @param retrying builds the retry layer over the pacing layer and the retry configuration
     * @return builder of the client
     */
    static DefaultGitHubClient.Builder assemble(
            final HttpTransport base,
            final AuthenticationProvider auth,
            final String userAgent,
            final GitHubClientConfiguration configuration,
            final Duration mutationInterval,
            final BiFunction<HttpTransport, RetryConfiguration, HttpTransport> retrying) {
        try {
            final HttpTransportConfiguration transport = configuration.transport();
            logStack(configuration, transport, mutationInterval);
            final HttpTransport retried = retrying.apply(
                    paced(
                            new ConcurrencyLimitingHttpTransport(
                                    base, transport.maxConcurrentRequests(), transport.maxSlotWait()),
                            mutationInterval),
                    githubRetry(configuration.retry()));
            final HttpTransport stack = new ConditionalCachingHttpTransport(
                    retried,
                    configuration.conditionalCacheMaxEntries(),
                    configuration.conditionalCacheMaxBodyBytes(),
                    configuration.conditionalCacheMaxBytes(),
                    CONDITIONAL_CACHE_VARYING_HEADERS);
            return configured(
                    DefaultGitHubClient.builder(stack, configuration.configuration(), auth), userAgent, configuration);
        } catch (final RuntimeException rejected) {
            // A decorator or the builder refused an argument, so no client owns the base transport yet, and it is
            // released here. The decorators own nothing else.
            logRefused(rejected);
            base.close();
            throw rejected;
        }
    }

    private static DefaultGitHubClient.Builder configured(
            final DefaultGitHubClient.Builder builder,
            final String userAgent,
            final GitHubClientConfiguration configuration) {
        builder.userAgent(userAgent).allowInsecureHttp(configuration.allowInsecureHttp());
        final GitHubRateLimitListener listener = configuration.rateLimitListener();
        if (listener != null) {
            builder.rateLimitListener(listener);
        }
        configuration.flavor().ifPresent(builder::flavor);
        configuration.ghesVersion().ifPresent(builder::ghesVersion);
        builder.pagination(configuration.pagination());
        final HttpCallObserver observer = configuration.callObserver();
        if (observer != null) {
            builder.callObserver(observer);
        }
        return builder;
    }

    private static void logStack(
            final GitHubClientConfiguration configuration,
            final HttpTransportConfiguration transport,
            final Duration mutationInterval) {
        LOG.atDebug()
                .setMessage("GitHub client stack for {}: at most {} concurrent request(s), mutation pacing {}, retry"
                        + " attempts {}, conditional cache {} entries")
                .addArgument(() ->
                        HttpUris.describeSafe(configuration.configuration().baseUri()))
                .addArgument(transport.maxConcurrentRequests())
                .addArgument(() -> mutationInterval.isZero() ? "off" : mutationInterval)
                .addArgument(() -> configuration.retry().attempts())
                .addArgument(configuration.conditionalCacheMaxEntries())
                .log();
    }

    private static void logRefused(final RuntimeException rejected) {
        LOG.atDebug()
                .setMessage("The GitHub client was refused with {}: {}; the transport is closed")
                .addArgument(rejected.getClass().getName())
                .addArgument(rejected.getMessage())
                .log();
    }

    // A zero interval, which only a GHES configuration allows, leaves the mutations unpaced.
    private static HttpTransport paced(final HttpTransport delegate, final Duration mutationInterval) {
        if (mutationInterval.isZero()) {
            return delegate;
        }
        return new MutationPacingHttpTransport(
                delegate, mutationInterval, MutationPacingHttpTransport.DEFAULT_MUTATION_METHODS);
    }

    private static RetryConfiguration githubRetry(final RetryConfiguration retry) {
        return retry.withRateLimitDetector(GitHubRateLimitDetector.INSTANCE);
    }
}
