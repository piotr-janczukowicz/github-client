package io.github.piotr_janczukowicz.github.client.exception;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import java.io.Serial;
import java.net.URI;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * GitHub understood the request and refused it (HTTP 403 that is not a rate limit).
 *
 * <p>The cause is not always a missing permission: GitHub also answers 403 for blocked or suspended access, a
 * repository or organization policy, a required SAML SSO authorization ({@link #ssoUrl()}), or an action that needs
 * admin rights. Read {@link #getMessage()} and {@link #documentationUrl()}. A 403 that is a rate-limit signal is
 * {@link GitHubRateLimitException}, not this type. A missing or invalid credential is
 * {@link GitHubUnauthorizedException}.
 */
public final class GitHubForbiddenException extends GitHubApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable String ssoUrl;

    /**
     * Creates a forbidden failure with request metadata.
     *
     * @param statusCode HTTP status from the response
     * @param message GitHub {@code message} field, or a fallback status line
     * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
     * @param rawBody response body as text, or {@code null}
     * @param ssoUrl SSO authorize URL from {@code X-GitHub-SSO}, or {@code null}
     * @param headers response headers
     * @param method HTTP method, or {@code null}
     * @param uri last-hop request URI, or {@code null}
     */
    public GitHubForbiddenException(
            final int statusCode,
            final String message,
            final @Nullable String documentationUrl,
            final @Nullable String rawBody,
            final @Nullable String ssoUrl,
            final HttpHeaders headers,
            final @Nullable HttpMethod method,
            final @Nullable URI uri) {
        super(statusCode, message, documentationUrl, rawBody, headers, method, uri);
        this.ssoUrl = ssoUrl;
    }

    /**
     * SSO authorize URL when GitHub sent {@code X-GitHub-SSO}.
     *
     * @return URL, or empty
     */
    public Optional<String> ssoUrl() {
        return Optional.ofNullable(ssoUrl);
    }
}
