package io.github.piotr_janczukowicz.github.client.internal;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.github.client.GitHubClientConfiguration;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitDetector;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.client.HttpCallObserver;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.transport.ConcurrencyLimitingHttpTransport;
import io.github.piotr_janczukowicz.http.transport.ConditionalCachingHttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
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
 * Builds the transport stack and the client on it. Exported only to the modules that build clients over their own
 * transport, such as the tests of the libraries on this one; it is not part of the API.
 */
public final class ClientStacks {

    private static final Logger LOG = LoggerFactory.getLogger(ClientStacks.class);

    // The representation of a GitHub response depends on Accept and on the REST API version, so both key the cache.
    private static final Set<String> CONDITIONAL_CACHE_VARYING_HEADERS =
            Set.of(HttpHeaderNames.ACCEPT, GitHubHeaders.API_VERSION);

    private ClientStacks() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Wraps {@code delegate} in the retry layer of the GitHub stack, with GitHub's rate-limit detector.
     *
     * @param delegate transport to retry
     * @param retry retry settings
     * @return retrying transport that closes {@code delegate} when it is closed
     * @throws NullPointerException if an argument is {@code null}
     */
    public static HttpTransport retrying(final HttpTransport delegate, final RetryConfiguration retry) {
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
    public static DefaultGitHubClient.Builder assemble(
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

    public static DefaultGitHubClient.Builder configured(
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

    public static void logRefused(final RuntimeException rejected) {
        LOG.atDebug()
                .setMessage("The GitHub client was refused with {}: {}; the transport is closed")
                .addArgument(rejected.getClass().getName())
                .addArgument(rejected.getMessage())
                .log();
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
