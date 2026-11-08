package io.github.piotr_janczukowicz.github.client;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.client.HttpClientExecutor;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for the GitHub REST API. Created by {@link GitHubClients}; the implementation is package-private.
 *
 * <p>Sends the credentials of its {@link AuthenticationProvider}, Accept, User-Agent, API version, and extra headers.
 * The {@code User-Agent} is required and set when the client is created with {@link GitHubClients}: GitHub asks for the
 * name of the application or the GitHub username of its owner so it can make contact when a client causes problems.
 * Configured extra headers cannot replace the credentials; overlay headers on a call replace same-named headers from
 * configuration, including {@code Authorization}.
 *
 * <p>HTTP 2xx become {@link HttpResponse}. Unsuccessful statuses become
 * {@link io.github.piotr_janczukowicz.github.client.exception.GitHubApiException} or a subtype. Transport failures are
 * {@link IOException}.
 *
 * <p>A client made by {@link GitHubClients#create} owns its transport, and {@link #close()} closes it. Every request
 * goes through the always-on stack of {@link GitHubClients}, outermost first: a conditional cache (a repeated GET is
 * sent with its validators, and a 304 is answered from memory), retry (waits out GitHub's rate limits and holds back
 * the other calls of the same credential meanwhile), mutation pacing (one second between mutations by default), a limit
 * on concurrent requests (1 by default, strictly serial as GitHub recommends), then the OkHttp transport. The
 * rate-limit behaviour is described in the <a
 * href="https://github.com/piotr-janczukowicz/github-rest-client/blob/main/docs/guide/rate-limiting.md">Rate
 * limiting</a> section of the README.
 *
 * <p><strong>Secondary rate limits.</strong> GitHub limits, besides the hourly quota, at most 100 concurrent requests
 * (enforced by {@link GitHubClients}, which by default sends one request at a time), 900 points per minute per endpoint
 * (GET, HEAD and OPTIONS cost 1 point, writes 5), 90 seconds of CPU time per 60 seconds, and about 80 content-creating
 * requests per minute and 500 per hour; it may apply others without saying so. This interface does not pace requests.
 * The client that {@link GitHubClients} builds does: it delays each mutation by one second unless configured otherwise.
 * The retry layer reacts to a 403 or 429 that signals a limit: it waits as GitHub's documentation requires
 * ({@code Retry-After}, else the reset when no requests remain, else at least 60 seconds, doubling on each repetition),
 * and the gate (see {@link io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport}) holds back the other
 * calls of the same credential meanwhile instead of letting them be rejected too. A program that creates content in
 * bulk must pace itself.
 *
 * <p>Thread safety: a client is safe to share between threads. Its transport stack and rate-limit state are built for
 * concurrent calls, and the {@link AuthenticationProvider} must be safe for concurrent use as well. Create one client
 * per credential and share it instead of creating one per thread or per call: each client has its own connection pool,
 * rate-limit state and conditional cache, so separate clients share none of them.
 *
 * <p>Always call {@link #close()}, for example with try-with-resources. Nothing closes a client for you, and a client
 * that is dropped without {@code close()} keeps its pooled sockets open until the connection keep-alive of
 * {@link io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration} expires (5 minutes by default), so a
 * program that creates many clients without closing them can run out of file descriptors.
 *
 * <p>A 401 asks the {@link AuthenticationProvider} to drop what it cached
 * ({@link AuthenticationProvider#isRenewableAfterRejection(io.github.piotr_janczukowicz.http.client.authentication.Authentication)});
 * if it does, the call is sent once more with the token it then returns. A base URI that uses plain {@code http} on a
 * host other than loopback is refused when the client is created unless {@link GitHubClientConfiguration} allows it
 * with {@code allowInsecureHttp(true)}.
 */
public sealed interface GitHubClient extends HttpClientExecutor, AutoCloseable permits DefaultGitHubClient {

    /** Value of {@code X-GitHub-Api-Version} on every request. */
    String API_VERSION = "2026-03-10";

    /** {@code Accept} sent unless the configuration's default headers set one ({@code application/vnd.github+json}). */
    String DEFAULT_ACCEPT = "application/vnd.github+json";

    /**
     * Latest quota GitHub reported for {@code resource} on a response to this client, from the {@code X-RateLimit-*}
     * headers. It is updated by every call, successful or not, so it costs no extra request, unlike {@code GET
     * /rate_limit}. Empty until a response for that resource has been seen. It is the latest one received: a response
     * of a later window replaces it, one of an earlier window does not, and within a window the newest wins even if its
     * {@code remaining} is higher, because GitHub can report different counts for one window. Do not depend on exact
     * counts. A response without {@code X-RateLimit-Resource} is kept under
     * {@value GitHubRateLimitSnapshot#UNKNOWN_RESOURCE}. A snapshot is not removed when its window resets, so check
     * {@link GitHubRateLimitSnapshot#resetAt()}. {@link GitHubRateLimitSnapshot#observedAt()} is the moment this quota
     * was first observed: a response that repeats the stored quota does not replace the snapshot. The client already
     * waits for and blocks on rate limits; use these values for monitoring and budgeting, not to pace calls yourself.
     *
     * @param resource rate-limit resource such as {@code core}, {@code search} or {@code graphql}
     * @return latest snapshot, or empty
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    Optional<GitHubRateLimitSnapshot> lastRateLimit(String resource);

    /**
     * Latest quota per resource seen so far; see {@link #lastRateLimit(String)}. The client already waits for and
     * blocks on rate limits; use these values for monitoring and budgeting, not to pace calls yourself.
     *
     * @return unmodifiable copy keyed by resource name; {@code observedAt} is the moment a quota was first observed
     */
    Map<String, GitHubRateLimitSnapshot> lastRateLimits();

    /**
     * Pagination settings of this client.
     *
     * @return page size and page-follow limits
     */
    PaginationConfiguration pagination();

    /**
     * The GitHub flavor this client is limited to, see {@link GitHubClientConfiguration#flavor()}.
     *
     * @return the flavor, or empty if the client does not check flavors
     */
    Optional<GitHubFlavor> flavor();

    /**
     * The GitHub Enterprise Server release this client is limited to, see
     * {@link GitHubClientConfiguration#ghesVersion()}.
     *
     * @return the release, or empty if the client is not a {@link GitHubFlavor#GHES} client
     */
    Optional<GhesVersion> ghesVersion();

    /**
     * The view of this client that talks to the GitHub Enterprise Server management console,
     * {@code https://HOST/manage/v1/}. It shares the transport stack, headers and error mapping of this client but has
     * its own origin guard, which accepts only the console, and it never asks the {@link AuthenticationProvider} for
     * credentials, so the token of this client is not sent to the console. The console is called with the explicit
     * credentials of {@link GitHubCaller#managementCall}. The console is reached on the port of the API, 443: GitHub
     * documents the Manage GHES API on 8443 (TLS) and 8080, so on an appliance that serves it there only, the calls can
     * fail; this limitation is not verified against a live appliance. The custom {@code defaultHeaders} of this client
     * are sent to the console too: set none that must not reach it. The client in turn refuses every request to the
     * console, because only this view is allowed to send one. Closing the view closes the transport it shares with this
     * client; close this client instead.
     *
     * @return the console view
     * @throws UnsupportedOperationException if this client has no console: its flavor is {@link GitHubFlavor#CLOUD} or
     *     {@link GitHubFlavor#GHEC}, its base URI is not {@code https://HOST/api/v3/}, the form of a GitHub Enterprise
     *     Server API, or it is itself the console view
     */
    GitHubClient management();

    /**
     * Closes the transport this client owns. It does not close the {@link AuthenticationProvider} of this client: an
     * auth that holds resources of its own, such as
     * {@link io.github.piotr_janczukowicz.github.client.app.GitHubInstallationAuthenticationProvider} with its token
     * client, is closed by whoever created it.
     */
    @Override
    void close();
}
