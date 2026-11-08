package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireBetween;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.http.client.HttpCallObserver;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration of the GitHub stack that {@link GitHubClients} builds around the transport. The stack is always the
 * same: a conditional cache, then retry (which waits out GitHub's rate limits), then mutation pacing, then a limit on
 * concurrent requests, then the OkHttp transport. No setting turns one of these layers off; {@link Builder#build()}
 * rejects a retry configuration that would disable the handling of rate limits, and a cache limit below one.
 *
 * <p>Defaults: one request in flight at a time, as GitHub's best practices recommend for serial requests; a conditional
 * cache of 64 responses, at most 1 MiB per body and 4 MiB of bodies in all; 60 seconds of waiting for a slot; and one
 * second between the starts of two mutations (POST, PATCH, PUT and DELETE). The one-second minimum applies to the
 * interval, so the pause between mutations cannot be shortened.
 *
 * <p>The concurrency limit and the slot wait are those of {@link #transport()},
 * {@link HttpTransportConfiguration#maxConcurrentRequests()} and {@link HttpTransportConfiguration#maxSlotWait()}.
 */
public final class GitHubClientConfiguration {

    /** The GitHub.com REST API, unless configured otherwise: {@code https://api.github.com/}. */
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.github.com/");

    /** Least time between the starts of two mutations, unless configured otherwise: 1 second, also the minimum. */
    public static final Duration DEFAULT_MUTATION_INTERVAL = Duration.ofSeconds(1);

    private static final Logger LOG = LoggerFactory.getLogger(GitHubClientConfiguration.class);
    private static final int MIN_CONCURRENT_REQUESTS = 1;
    private static final int MAX_CONCURRENT_REQUESTS = 100;
    private static final int MIN_RATE_LIMIT_ATTEMPTS = 2;

    private final HttpClientConfiguration configuration;
    private final HttpTransportConfiguration transport;
    private final RetryConfiguration retry;
    private final Duration mutationInterval;
    private final int conditionalCacheMaxEntries;
    private final int conditionalCacheMaxBodyBytes;
    private final long conditionalCacheMaxBytes;
    private final @Nullable GitHubRateLimitListener rateLimitListener;
    private final @Nullable HttpCallObserver callObserver;
    private final boolean isInsecureHttpAllowed;
    private final @Nullable GitHubFlavor flavor;
    private final @Nullable GhesVersion ghesVersion;
    private final PaginationConfiguration pagination;

    private GitHubClientConfiguration(
            final HttpClientConfiguration configuration,
            final HttpTransportConfiguration transport,
            final RetryConfiguration retry,
            final Duration mutationInterval,
            final int conditionalCacheMaxEntries,
            final int conditionalCacheMaxBodyBytes,
            final long conditionalCacheMaxBytes,
            final @Nullable GitHubRateLimitListener rateLimitListener,
            final @Nullable HttpCallObserver callObserver,
            final boolean isInsecureHttpAllowed,
            final @Nullable GitHubFlavor flavor,
            final @Nullable GhesVersion ghesVersion,
            final PaginationConfiguration pagination) {
        this.configuration = configuration;
        this.transport = transport;
        this.retry = retry;
        this.mutationInterval = mutationInterval;
        this.conditionalCacheMaxEntries = conditionalCacheMaxEntries;
        this.conditionalCacheMaxBodyBytes = conditionalCacheMaxBodyBytes;
        this.conditionalCacheMaxBytes = conditionalCacheMaxBytes;
        this.rateLimitListener = rateLimitListener;
        this.callObserver = callObserver;
        this.isInsecureHttpAllowed = isInsecureHttpAllowed;
        this.flavor = flavor;
        this.ghesVersion = ghesVersion;
        this.pagination = pagination;
    }

    /**
     * Configuration with every default: the GitHub.com API, a transport with one request in flight at a time and 60
     * seconds of waiting for a slot, {@link RetryConfiguration#defaults()}, one second between mutations, the default
     * conditional cache limits, the default pagination, and no listener or observer.
     *
     * @return default configuration
     */
    public static GitHubClientConfiguration defaults() {
        return builder().build();
    }

    /**
     * Builder initialized to {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Base URI and extra default headers of the client.
     *
     * @return HTTP client configuration
     */
    public HttpClientConfiguration configuration() {
        return configuration;
    }

    /**
     * Transport of the client: timeouts, proxy, TLS, response size limit, connection pool, and the limit on requests in
     * flight with the wait for a slot. The default allows one request in flight at a time, as GitHub recommends.
     *
     * <p>GitHub's best practices recommend one request at a time, which serializes calls, so the throughput of one
     * client stays flat however many threads share it (a small GET took about 170 microseconds in a local benchmark,
     * roughly 5,800 calls per second). Raising the limit raises throughput, and the risk of GitHub's secondary rate
     * limits.
     *
     * @return transport configuration
     */
    public HttpTransportConfiguration transport() {
        return transport;
    }

    /**
     * Retry settings. Rate-limited responses are always retried and rate limits are always coordinated across calls.
     *
     * @return retry configuration
     */
    public RetryConfiguration retry() {
        return retry;
    }

    /**
     * Least time between the starts of two mutations of a client. Only the pause before a request is affected; a GET is
     * never delayed.
     *
     * @return duration of at least one second
     */
    public Duration mutationInterval() {
        return mutationInterval;
    }

    /**
     * Responses the conditional cache keeps, at least one. The least recently used one is dropped beyond this limit.
     *
     * @return limit of at least 1; the cache cannot be switched off
     */
    public int conditionalCacheMaxEntries() {
        return conditionalCacheMaxEntries;
    }

    /**
     * Largest body the conditional cache keeps for one response, at least one byte. A larger response is passed on but
     * not kept.
     *
     * @return limit of at least 1
     */
    public int conditionalCacheMaxBodyBytes() {
        return conditionalCacheMaxBodyBytes;
    }

    /**
     * Bytes of bodies the conditional cache keeps in all, at least one. The least recently used entries are dropped
     * until the bodies kept fit.
     *
     * @return limit of at least 1 byte
     */
    public long conditionalCacheMaxBytes() {
        return conditionalCacheMaxBytes;
    }

    /**
     * Listener that gets the rate-limit quota of every response.
     *
     * @return listener, or {@code null} if none was set
     */
    public @Nullable GitHubRateLimitListener rateLimitListener() {
        return rateLimitListener;
    }

    /**
     * Observer that gets every finished call.
     *
     * @return observer, or {@code null} if none was set
     */
    public @Nullable HttpCallObserver callObserver() {
        return callObserver;
    }

    /**
     * Whether a plain-{@code http} base URI on a host other than loopback is accepted.
     *
     * @return {@code true} if such a base URI is accepted
     */
    public boolean allowInsecureHttp() {
        return isInsecureHttpAllowed;
    }

    /**
     * The GitHub flavor whose REST description the client is limited to. When set, a method that exists in other
     * flavors only (see {@link io.github.piotr_janczukowicz.github.api.Flavors}) throws
     * {@link UnsupportedOperationException} before any request is sent.
     *
     * @return the flavor, or empty if the client does not check flavors (the default)
     */
    public Optional<GitHubFlavor> flavor() {
        return Optional.ofNullable(flavor);
    }

    /**
     * The GitHub Enterprise Server release of a {@link GitHubFlavor#GHES} client. A method that exists only in some
     * releases (see {@link io.github.piotr_janczukowicz.github.api.GhesVersions}) throws
     * {@link UnsupportedOperationException} before any request is sent when the release is not among them.
     *
     * @return the release, or empty if the flavor is not {@link GitHubFlavor#GHES}
     */
    public Optional<GhesVersion> ghesVersion() {
        return Optional.ofNullable(ghesVersion);
    }

    /**
     * Page size and page-follow limits of list endpoints.
     *
     * @return pagination settings
     */
    public PaginationConfiguration pagination() {
        return pagination;
    }

    /** Builds a {@link GitHubClientConfiguration}. Unset fields keep the {@link #defaults()} values. */
    public static final class Builder {
        private HttpClientConfiguration configuration =
                HttpClientConfiguration.builder(DEFAULT_BASE_URI).build();
        private HttpTransportConfiguration transport =
                HttpTransportConfiguration.builder().maxConcurrentRequests(1).build();
        private RetryConfiguration retry = RetryConfiguration.defaults();
        private Duration mutationInterval = DEFAULT_MUTATION_INTERVAL;
        private int conditionalCacheMaxEntries = 64;
        private int conditionalCacheMaxBodyBytes = 1024 * 1024;
        private long conditionalCacheMaxBytes = 4L * 1024 * 1024;
        private @Nullable GitHubRateLimitListener rateLimitListener;
        private @Nullable HttpCallObserver callObserver;
        private boolean isInsecureHttpAllowed;
        private @Nullable GitHubFlavor flavor;
        private @Nullable GhesVersion ghesVersion;
        private PaginationConfiguration pagination = PaginationConfiguration.defaults();

        private Builder() {}

        /**
         * Base URI and extra default headers of the client. Defaults to the GitHub.com API.
         *
         * @param configuration HTTP client configuration
         * @return this builder
         * @throws NullPointerException if {@code configuration} is {@code null}
         */
        public Builder configuration(final HttpClientConfiguration configuration) {
            this.configuration = requireNonNull(configuration, "configuration");
            return this;
        }

        /**
         * Transport of the client: timeouts, proxy, TLS, response size limit, connection pool, and the limit on
         * requests in flight with the wait for a slot. Defaults to one request in flight at a time, as GitHub
         * recommends; raising {@code maxConcurrentRequests} is allowed, but increases the risk of secondary rate
         * limits.
         *
         * @param transport transport configuration; its {@code maxConcurrentRequests} must be between 1 and 100
         * @return this builder
         * @throws NullPointerException if {@code transport} is {@code null}
         */
        public Builder transport(final HttpTransportConfiguration transport) {
            this.transport = requireNonNull(transport, "transport");
            return this;
        }

        /**
         * Retry settings. Defaults to {@link RetryConfiguration#defaults()}. GitHub clients always use the GitHub
         * rate-limit detector, so the configuration must keep the default {@link RetryConfiguration#rateLimitDetector()
         * rate-limit detector}; {@code RetryConfiguration.Builder.rateLimitDetector} is for {@code HttpClient} users
         * only.
         *
         * @param retry retry configuration with the default rate-limit detector
         * @return this builder
         * @throws NullPointerException if {@code retry} is {@code null}
         * @throws IllegalArgumentException if {@code retry} carries a rate-limit detector other than the default one,
         *     because GitHub clients always use the GitHub rate-limit detector
         */
        public Builder retry(final RetryConfiguration retry) {
            requireNonNull(retry, "retry");
            if (!retry.hasDefaultRateLimitDetector()) {
                throw new IllegalArgumentException("retry must keep the default rate-limit detector: "
                        + "GitHub clients always use the GitHub rate-limit detector");
            }
            this.retry = retry;
            return this;
        }

        /**
         * Least time between the starts of two mutations. Defaults to {@link #DEFAULT_MUTATION_INTERVAL}. The floor of
         * one second protects against the content-creation limits of GitHub.com; a {@link GitHubFlavor#GHES} client may
         * use any interval from zero up, and zero switches the pacing off, because a server has its own limits.
         *
         * @param mutationInterval duration of at least one second, or at least zero for the GHES flavor
         * @return this builder
         * @throws NullPointerException if {@code mutationInterval} is {@code null}
         */
        public Builder mutationInterval(final Duration mutationInterval) {
            this.mutationInterval = requireNonNull(mutationInterval, "mutationInterval");
            return this;
        }

        /**
         * Responses the conditional cache keeps; the least recently used one is dropped beyond this limit. Defaults to
         * 64. The cache cannot be switched off, so the limit is at least 1.
         *
         * @param conditionalCacheMaxEntries limit of at least 1
         * @return this builder
         */
        public Builder conditionalCacheMaxEntries(final int conditionalCacheMaxEntries) {
            this.conditionalCacheMaxEntries = conditionalCacheMaxEntries;
            return this;
        }

        /**
         * Largest body the conditional cache keeps for one response. Defaults to 1 MiB; a larger response is passed on
         * but not kept. The limit is at least 1 byte.
         *
         * @param conditionalCacheMaxBodyBytes limit of at least 1
         * @return this builder
         */
        public Builder conditionalCacheMaxBodyBytes(final int conditionalCacheMaxBodyBytes) {
            this.conditionalCacheMaxBodyBytes = conditionalCacheMaxBodyBytes;
            return this;
        }

        /**
         * Bytes of bodies the conditional cache keeps in all. Defaults to 4 MiB. The least recently used entries are
         * dropped until the bodies kept fit. The limit is at least 1 byte.
         *
         * @param conditionalCacheMaxBytes limit of at least 1 byte
         * @return this builder
         */
        public Builder conditionalCacheMaxBytes(final long conditionalCacheMaxBytes) {
            this.conditionalCacheMaxBytes = conditionalCacheMaxBytes;
            return this;
        }

        /**
         * Listener that gets the rate-limit quota of every response; see {@link GitHubRateLimitListener}.
         *
         * @param rateLimitListener listener, or {@code null} for none
         * @return this builder
         */
        public Builder rateLimitListener(final @Nullable GitHubRateLimitListener rateLimitListener) {
            this.rateLimitListener = rateLimitListener;
            return this;
        }

        /**
         * Observer that gets every finished call; see {@link HttpCallObserver}.
         *
         * @param callObserver observer, or {@code null} for none
         * @return this builder
         */
        public Builder callObserver(final @Nullable HttpCallObserver callObserver) {
            this.callObserver = callObserver;
            return this;
        }

        /**
         * Allows a plain-{@code http} base URI on a host other than loopback. Refused by default, because every request
         * would send the bearer token unencrypted; use it only on a trusted network.
         *
         * @param allowInsecureHttp {@code true} to accept such a base URI
         * @return this builder
         */
        public Builder allowInsecureHttp(final boolean allowInsecureHttp) {
            this.isInsecureHttpAllowed = allowInsecureHttp;
            return this;
        }

        /**
         * Limits the client to the methods of one GitHub flavor: GitHub.com ({@link GitHubFlavor#CLOUD}), GitHub
         * Enterprise Cloud ({@link GitHubFlavor#GHEC}) or GitHub Enterprise Server ({@link GitHubFlavor#GHES}). A
         * method that the flavor's REST description does not carry then throws {@link UnsupportedOperationException}
         * before any request. The first two use {@code https://api.github.com} in their REST descriptions, so the
         * flavor never changes the base URI. GitHub Enterprise Cloud with data residency is served from
         * {@code https://api.SUBDOMAIN.ghe.com}: set that host with the HTTP client's base URI, separately from the
         * flavor. A {@link GitHubFlavor#GHES} client also needs {@link #ghesVersion(GhesVersion)} and a base URI that
         * has the path {@code /api/v3/}. Defaults to none: every method is allowed.
         *
         * @param flavor flavor to enforce, or {@code null} to allow every method
         * @return this builder
         */
        public Builder flavor(final @Nullable GitHubFlavor flavor) {
            this.flavor = flavor;
            return this;
        }

        /**
         * The GitHub Enterprise Server release the client talks to. Required with {@link GitHubFlavor#GHES}, refused
         * with any other flavor, and never defaulted, so a client does not silently target a release that was not
         * chosen. Only the releases in {@link GhesVersion#supported()} are accepted: a server that is newer than the
         * library needs a library release first.
         *
         * @param ghesVersion the release, or {@code null} for none
         * @return this builder
         */
        public Builder ghesVersion(final @Nullable GhesVersion ghesVersion) {
            this.ghesVersion = ghesVersion;
            return this;
        }

        /**
         * Page size and page-follow limits for list endpoints. Defaults to {@link PaginationConfiguration#defaults()}.
         *
         * @param pagination pagination settings
         * @return this builder
         * @throws NullPointerException if {@code pagination} is {@code null}
         */
        public Builder pagination(final PaginationConfiguration pagination) {
            this.pagination = requireNonNull(pagination, "pagination");
            return this;
        }

        /**
         * Validates and builds the configuration.
         *
         * @return configuration
         * @throws NullPointerException if a configuration, transport, retry or interval argument is {@code null}
         * @throws IllegalArgumentException if the transport's {@code maxConcurrentRequests} is outside 1 to 100,
         *     {@code mutationInterval} is shorter than one second (negative for the GHES flavor), a conditional cache
         *     limit is below one, {@code retry} does not retry rate-limited responses, does not coordinate rate limits,
         *     or allows fewer than two rate-limit attempts; or if the {@link GitHubFlavor#GHES} flavor lacks a
         *     supported {@link #ghesVersion(GhesVersion)} or a base URI with the path {@code /api/v3/}, or a release is
         *     set for another flavor
         */
        public GitHubClientConfiguration build() {
            requireNonNull(configuration, "configuration");
            requireNonNull(transport, "transport");
            requireBetween(
                    transport.maxConcurrentRequests(),
                    MIN_CONCURRENT_REQUESTS,
                    MAX_CONCURRENT_REQUESTS,
                    "maxConcurrentRequests");
            requireRateLimitHandling(retry);
            requireAtLeast(
                    mutationInterval,
                    flavor == null ? DEFAULT_MUTATION_INTERVAL : flavor.minMutationInterval(),
                    "mutationInterval");
            requireAtLeast(conditionalCacheMaxEntries, 1, "conditionalCacheMaxEntries");
            requireAtLeast(conditionalCacheMaxBodyBytes, 1, "conditionalCacheMaxBodyBytes");
            requireAtLeast(conditionalCacheMaxBytes, 1L, "conditionalCacheMaxBytes");
            requireGhesSettings();
            return new GitHubClientConfiguration(
                    configuration,
                    transport,
                    retry,
                    mutationInterval,
                    conditionalCacheMaxEntries,
                    conditionalCacheMaxBodyBytes,
                    conditionalCacheMaxBytes,
                    rateLimitListener,
                    callObserver,
                    isInsecureHttpAllowed,
                    flavor,
                    ghesVersion,
                    pagination);
        }

        private void requireGhesSettings() {
            if (flavor != GitHubFlavor.GHES) {
                if (ghesVersion != null) {
                    throw new IllegalArgumentException(
                            "ghesVersion needs the GHES flavor, but the flavor is " + flavor);
                }
                warnIfGhesBase();
                return;
            }
            final List<GhesVersion> supported = GhesVersion.supported();
            if (ghesVersion == null) {
                throw new IllegalArgumentException(
                        "ghesVersion is required for the GHES flavor; supported versions: " + supported);
            }
            if (!supported.contains(ghesVersion)) {
                throw new IllegalArgumentException("GitHub Enterprise Server " + ghesVersion
                        + " is not supported; supported versions: " + supported);
            }
            if (!new GitHubApiBase(configuration.baseUri()).isGhes()) {
                throw new IllegalArgumentException("the base URI of a GHES client must be https://host"
                        + GitHubApiBase.GHES_API_PATH + ", but its path is: "
                        + configuration.baseUri().getPath());
            }
        }

        private void warnIfGhesBase() {
            if (flavor != null && new GitHubApiBase(configuration.baseUri()).isGhes()) {
                LOG.atWarn()
                        .setMessage(
                                "The flavor is {} but the base URI is a GitHub Enterprise Server API ({}): the flavor,"
                                        + " not the host, decides which methods are available")
                        .addArgument(flavor)
                        .addArgument(HttpUris.describeSafe(configuration.baseUri()))
                        .log();
            }
        }

        private static void requireRateLimitHandling(final RetryConfiguration retry) {
            requireNonNull(retry, "retry");
            if (!retry.retryable().retryRateLimitedResponses()) {
                throw new IllegalArgumentException(
                        "retry must retry rate-limited responses: GitHub's rate limits are always waited out");
            }
            if (!retry.coordinateRateLimits()) {
                throw new IllegalArgumentException("retry must coordinate rate limits across calls");
            }
            requireAtLeast(retry.attempts().maxRateLimitAttempts(), MIN_RATE_LIMIT_ATTEMPTS, "maxRateLimitAttempts");
        }
    }
}
