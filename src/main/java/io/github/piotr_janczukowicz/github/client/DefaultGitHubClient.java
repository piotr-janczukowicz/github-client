package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.USER_AGENT;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.client.HttpCallObserver;
import io.github.piotr_janczukowicz.http.client.HttpClient;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.HttpOriginGuard;
import io.github.piotr_janczukowicz.http.client.RedirectPolicy;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Package-private implementation of {@link GitHubClient}, built by {@link GitHubClients}. */
final class DefaultGitHubClient extends HttpClient implements GitHubClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultGitHubClient.class);

    private static final String USER_AGENT_PARAMETER = "userAgent";

    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    private final PaginationConfiguration pagination;
    private final GitHubRateLimitTracker rateLimits;
    private final @Nullable GitHubFlavor flavor;
    private final @Nullable GhesVersion ghesVersion;
    private final @Nullable GitHubClient management;

    /**
     * Client over the given parts; {@link Builder#build()} uses it.
     *
     * @param http builder of the underlying HTTP client, with its error mapper and observers
     * @param pagination page size and page-follow limits
     * @param rateLimits quota of this client, fed by the response headers observer of {@code http}
     * @param flavor flavor to enforce, or {@code null} to allow every method
     * @param ghesVersion release of a {@link GitHubFlavor#GHES} client, or {@code null}
     * @param management console view of a GitHub Enterprise Server client, or {@code null}
     * @throws NullPointerException if {@code http}, {@code pagination} or {@code rateLimits} is {@code null}
     */
    DefaultGitHubClient(
            final HttpClient.Builder http,
            final PaginationConfiguration pagination,
            final GitHubRateLimitTracker rateLimits,
            final @Nullable GitHubFlavor flavor,
            final @Nullable GhesVersion ghesVersion,
            final @Nullable GitHubClient management) {
        super(http);
        this.pagination = requireNonNull(pagination, "pagination");
        this.rateLimits = requireNonNull(rateLimits, "rateLimits");
        this.flavor = flavor;
        this.ghesVersion = ghesVersion;
        this.management = management;
    }

    @Override
    public Optional<GitHubFlavor> flavor() {
        return Optional.ofNullable(flavor);
    }

    @Override
    public Optional<GhesVersion> ghesVersion() {
        return Optional.ofNullable(ghesVersion);
    }

    @Override
    public GitHubClient management() {
        if (management == null) {
            throw new UnsupportedOperationException(
                    "This client has no management console: only a client of a GitHub Enterprise Server API,"
                            + " https://HOST" + GitHubApiBase.GHES_API_PATH
                            + ", has one, and its console view has none of its own; the base URI is "
                            + HttpUris.describeSafe(baseUri()) + ", the flavor is " + flavor);
        }
        return management;
    }

    @Override
    public Optional<GitHubRateLimitSnapshot> lastRateLimit(final String resource) {
        return rateLimits.last(resource);
    }

    @Override
    public Map<String, GitHubRateLimitSnapshot> lastRateLimits() {
        return rateLimits.all();
    }

    @Override
    public PaginationConfiguration pagination() {
        return pagination;
    }

    /**
     * Checks a {@code User-Agent} value: not blank, visible ASCII only (letters, digits, punctuation and spaces).
     *
     * @param userAgent value to check
     * @return {@code userAgent}
     * @throws NullPointerException if {@code userAgent} is {@code null}
     * @throws IllegalArgumentException if {@code userAgent} is blank or has other characters
     */
    static String requireValidUserAgent(final String userAgent) {
        requireNotBlank(userAgent, USER_AGENT_PARAMETER);
        for (int i = 0; i < userAgent.length(); i++) {
            final char c = userAgent.charAt(i);
            if (c < ' ' || c > '~') {
                throw new IllegalArgumentException("userAgent must contain visible ASCII characters only");
            }
        }
        return userAgent;
    }

    /**
     * Builder over {@code transport}, which the client owns and closes. Only {@link GitHubClients} and the test helpers
     * build clients this way. The caller composes the transport: {@link GitHubClients} stacks the cache, retry, pacing
     * and limits on it as decorators. A transport without a retry layer that knows GitHub's rate limits (GitHub signals
     * most of them as HTTP 403) returns them unretried, and they surface as
     * {@link io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException}.
     *
     * @param transport transport the client sends requests through
     * @param configuration base URI, extra default headers and pagination; the default headers may set {@code Accept}
     *     but not {@code User-Agent}, which is set with {@link Builder#userAgent(String)}, nor
     *     {@code X-GitHub-Api-Version}, which is always {@link GitHubClient#API_VERSION}
     * @param auth source of the credentials, read on every request
     * @return builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the default headers set {@code X-GitHub-Api-Version} or {@code User-Agent}
     */
    static Builder builder(
            final HttpTransport transport,
            final HttpClientConfiguration configuration,
            final AuthenticationProvider auth) {
        return new Builder(transport, configuration, auth);
    }

    static boolean isLoopbackHost(final @Nullable String host) {
        final String name = host == null ? "" : host;
        // An IP literal is parsed, never resolved, so a host name cannot pass as one.
        return "localhost".equalsIgnoreCase(name)
                || name.toLowerCase(Locale.ROOT).endsWith(".localhost")
                || IPV4_LOOPBACK.matcher(name).matches()
                || (name.startsWith("[") && name.endsWith("]") && isLoopbackIpv6(name));
    }

    private static boolean isLoopbackIpv6(final String literal) {
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (final UnknownHostException unused) {
            // Not an IPv6 literal.
            return false;
        }
    }

    /** Client factory with a fixed transport, configuration and codec and an optional rate-limit listener. */
    static final class Builder {
        private final HttpTransport transport;
        private final HttpClientConfiguration configuration;
        private final AuthenticationProvider auth;
        private PaginationConfiguration pagination = PaginationConfiguration.defaults();
        private @Nullable GitHubRateLimitListener rateLimitListener;
        private @Nullable HttpCallObserver callObserver;
        private @Nullable String userAgent;
        private boolean isInsecureHttpAllowed;
        private @Nullable GitHubFlavor flavor;
        private @Nullable GhesVersion ghesVersion;
        private Clock clock = Clock.systemUTC();

        private Builder(
                final HttpTransport transport,
                final HttpClientConfiguration configuration,
                final AuthenticationProvider auth) {
            this.transport = requireNonNull(transport, "transport");
            this.configuration = requireNonNull(configuration, "configuration");
            this.auth = requireNonNull(auth, "auth");
            if (configuration.defaultHeaders().get(GitHubHeaders.API_VERSION).isPresent()) {
                throw new IllegalArgumentException("defaultHeaders must not set " + GitHubHeaders.API_VERSION
                        + ": the client always sends " + API_VERSION
                        + ", the version its models and endpoints were written for");
            }
            if (configuration.defaultHeaders().get(USER_AGENT).isPresent()) {
                throw new IllegalArgumentException(
                        "defaultHeaders must not set " + USER_AGENT + ": use Builder.userAgent(String)");
            }
        }

        /**
         * {@code User-Agent} of every request. Required. GitHub rejects requests without one and asks for the name of
         * the application, or the GitHub username of its owner, so that it can make contact when the client causes
         * problems; use something that identifies your application, such as {@code my-app/1.2.0}. It must be visible
         * ASCII. A {@code User-Agent} header set on a single call replaces it for that call.
         *
         * @param userAgent application name, optionally with a version
         * @return this builder
         * @throws NullPointerException if {@code userAgent} is {@code null}
         * @throws IllegalArgumentException if {@code userAgent} is blank or has characters other than visible ASCII
         */
        Builder userAgent(final String userAgent) {
            this.userAgent = requireValidUserAgent(userAgent);
            return this;
        }

        /**
         * Page size and page-follow limits for list endpoints. Defaults to {@link PaginationConfiguration#defaults()}.
         *
         * @param pagination pagination settings
         * @return this builder
         * @throws NullPointerException if {@code pagination} is {@code null}
         */
        Builder pagination(final PaginationConfiguration pagination) {
            this.pagination = requireNonNull(pagination, "pagination");
            return this;
        }

        /**
         * Reports the rate-limit quota of every response to {@code listener}; see {@link GitHubRateLimitListener}. The
         * latest quota is available from {@link GitHubClient#lastRateLimit(String)} with or without a listener.
         *
         * @param listener receives the quota of each response that carries one
         * @return this builder
         * @throws NullPointerException if {@code listener} is {@code null}
         */
        Builder rateLimitListener(final GitHubRateLimitListener listener) {
            this.rateLimitListener = requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Reports every finished call to {@code observer}, for metrics, tracing or audit logging: method, target
         * without query, status, response headers (so {@code X-GitHub-Request-Id} and the rate-limit headers) and the
         * time taken, or the failure. See {@link HttpCallObserver} for the threading rules.
         *
         * @param observer receives one event per call that reached the transport
         * @return this builder
         * @throws NullPointerException if {@code observer} is {@code null}
         */
        Builder callObserver(final HttpCallObserver observer) {
            this.callObserver = requireNonNull(observer, "observer");
            return this;
        }

        /**
         * Allows a base URI that uses plain {@code http} on a host other than loopback. By default {@link #build()}
         * refuses it, because every request would send the credentials unencrypted; a loopback host ({@code localhost},
         * {@code 127.0.0.0/8}, {@code [::1]}) is always allowed. Use it only for a trusted network, such as a GitHub
         * Enterprise Server reached through a TLS-terminating proxy inside it. A warning is logged when it takes
         * effect.
         *
         * @param allowInsecureHttp {@code true} to accept a plain-http base URI on a non-loopback host
         * @return this builder
         */
        Builder allowInsecureHttp(final boolean allowInsecureHttp) {
            this.isInsecureHttpAllowed = allowInsecureHttp;
            return this;
        }

        /**
         * Flavor the client enforces, see {@link GitHubClientConfiguration#flavor()}.
         *
         * @param flavor flavor to enforce
         * @return this builder
         */
        Builder flavor(final GitHubFlavor flavor) {
            this.flavor = requireNonNull(flavor, "flavor");
            return this;
        }

        /**
         * GitHub Enterprise Server release the client enforces, see {@link GitHubClientConfiguration#ghesVersion()}.
         *
         * @param ghesVersion release to enforce
         * @return this builder
         */
        Builder ghesVersion(final GhesVersion ghesVersion) {
            this.ghesVersion = requireNonNull(ghesVersion, "ghesVersion");
            return this;
        }

        /**
         * Builds the client.
         *
         * @return client
         * @throws NullPointerException if {@link #userAgent(String)} was not called
         * @throws IllegalStateException if the base URI is plain {@code http} on a non-loopback host and
         *     {@link #allowInsecureHttp(boolean)} was not set
         * @apiNote A failed build, whether a setting is refused or the client cannot be constructed, closes the
         *     transport first, so a failed build of a root client leaves nothing open for the caller to release.
         */
        DefaultGitHubClient build() {
            try {
                requireBuildable();
                final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(rateLimitListener, clock);
                LOG.atDebug()
                        .setMessage("Building a GitHub client for {}: flavor {}, GHES version {}, API version {}")
                        .addArgument(() -> HttpUris.describeSafe(configuration.baseUri()))
                        .addArgument(flavor)
                        .addArgument(ghesVersion)
                        .addArgument(API_VERSION)
                        .log();
                return new DefaultGitHubClient(
                        httpBuilder(
                                tracker, configuration.baseUri(), new GitHubOriginGuard(configuration.baseUri()), auth),
                        pagination,
                        tracker,
                        flavor,
                        ghesVersion,
                        managementView(tracker));
            } catch (final RuntimeException failed) {
                LOG.atDebug()
                        .setMessage("Building the GitHub client failed with {}: {}; the transport is closed")
                        .addArgument(failed.getClass().getName())
                        .addArgument(failed.getMessage())
                        .log();
                transport.close();
                throw failed;
            }
        }

        /**
         * Clock that stamps the rate-limit quota of each response, see {@link GitHubClient#lastRateLimit(String)}.
         * Defaults to {@link Clock#systemUTC()}; tests pass a fixed clock so the stamp is known.
         *
         * @param clock clock that stamps each observed quota
         * @return this builder
         * @throws NullPointerException if {@code clock} is {@code null}
         */
        Builder clock(final Clock clock) {
            this.clock = requireNonNull(clock, "clock");
            return this;
        }

        private void requireBuildable() {
            requireNonNull(userAgent, USER_AGENT_PARAMETER);
            requireSecureOrAllowed(configuration.baseUri());
        }

        // The console of a GHES host, https://HOST/manage/v1/: the same stack, an origin guard of its own and no auth.
        private @Nullable GitHubClient managementView(final GitHubRateLimitTracker tracker) {
            final boolean isGhesFlavor = flavor == null || flavor == GitHubFlavor.GHES;
            final Optional<URI> console = new GitHubApiBase(configuration.baseUri()).consoleUri();
            if (!isGhesFlavor || console.isEmpty()) {
                return null;
            }
            final URI consoleUri = console.get();
            return new DefaultGitHubClient(
                    httpBuilder(tracker, consoleUri, new HttpOriginGuard(consoleUri), AuthenticationProviders.none()),
                    pagination,
                    tracker,
                    flavor,
                    ghesVersion,
                    null);
        }

        private HttpClient.Builder httpBuilder(
                final GitHubRateLimitTracker tracker,
                final URI baseUri,
                final HttpOriginGuard originGuard,
                final AuthenticationProvider authentication) {
            final HttpClientConfiguration httpConfig =
                    new HttpClientConfiguration(baseUri, githubHeaders(configuration.defaultHeaders(), userAgent));
            final HttpClient.Builder http = HttpClient.builder(transport, httpConfig)
                    .originGuard(originGuard)
                    .redirectPolicy(RedirectPolicy.defaults())
                    .errorMapper(new GitHubErrorMapper(clock))
                    .auth(authentication)
                    .responseHeadersObserver(responseHeadersObserver(tracker));
            if (callObserver != null) {
                http.callObserver(callObserver);
            }
            return http;
        }

        private static Consumer<HttpHeaders> responseHeadersObserver(final GitHubRateLimitTracker tracker) {
            return headers -> {
                tracker.observe(headers);
                GitHubSsoHeaders.logPartialResults(headers);
            };
        }

        private static HttpHeaders githubHeaders(final HttpHeaders defaultHeaders, final @Nullable String userAgent) {
            return HttpHeaders.of(
                            ACCEPT,
                            DEFAULT_ACCEPT,
                            USER_AGENT,
                            requireNonNull(userAgent, USER_AGENT_PARAMETER),
                            GitHubHeaders.API_VERSION,
                            API_VERSION)
                    .merge(defaultHeaders);
        }

        private void requireSecureOrAllowed(final URI baseUri) {
            if (!"http".equalsIgnoreCase(baseUri.getScheme()) || isLoopbackHost(baseUri.getHost())) {
                return;
            }
            if (!isInsecureHttpAllowed) {
                throw new IllegalStateException("GitHub base URI " + HttpUris.describeSafe(baseUri)
                        + " is plain http, which would send the credentials unencrypted;"
                        + " use https, or call allowInsecureHttp(true) for a trusted network");
            }
            LOG.atWarn()
                    .setMessage("GitHub base URI {} is plain http: the credentials are sent unencrypted")
                    .addArgument(HttpUris.describeSafe(baseUri))
                    .log();
        }
    }
}
