package io.github.piotr_janczukowicz.github;

/**
 * Header names that GitHub defines beyond the standard ones in
 * {@link io.github.piotr_janczukowicz.http.HttpHeaderNames}.
 */
public final class GitHubHeaders {

    /** {@code X-GitHub-Api-Version}, which selects the version of the REST API. */
    public static final String API_VERSION = "X-GitHub-Api-Version";

    /** {@code X-GitHub-Request-Id}, which identifies a response for support requests. */
    public static final String REQUEST_ID = "X-GitHub-Request-Id";

    /** {@code X-GitHub-SSO}, which lists the organizations that need SAML single sign-on authorization. */
    public static final String SSO = "X-GitHub-SSO";

    /** {@code X-RateLimit-Limit}, the request quota of the window. */
    public static final String RATE_LIMIT_LIMIT = "X-RateLimit-Limit";

    /** {@code X-RateLimit-Remaining}, the requests left in the window. */
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";

    /** {@code X-RateLimit-Used}, the requests used in the window. */
    public static final String RATE_LIMIT_USED = "X-RateLimit-Used";

    /** {@code X-RateLimit-Reset}, the time at which the window resets, in epoch seconds. */
    public static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";

    /** {@code X-RateLimit-Resource}, the resource group that the quota applies to. */
    public static final String RATE_LIMIT_RESOURCE = "X-RateLimit-Resource";

    /** {@code X-Poll-Interval}: whole seconds that GitHub advises between polls of a resource. */
    public static final String POLL_INTERVAL = "X-Poll-Interval";

    private GitHubHeaders() {
        throw new UnsupportedOperationException("Utility class");
    }
}
