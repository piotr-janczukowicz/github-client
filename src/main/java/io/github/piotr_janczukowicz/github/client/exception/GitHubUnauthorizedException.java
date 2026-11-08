package io.github.piotr_janczukowicz.github.client.exception;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import java.io.Serial;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * The request carried no valid credential (HTTP 401): the token is missing, expired, revoked or malformed.
 *
 * <p>An authenticated caller without permission gets {@link GitHubForbiddenException} (403) or, for a private resource,
 * {@link GitHubNotFoundException} (404).
 */
public final class GitHubUnauthorizedException extends GitHubApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a 401 failure with request metadata.
     *
     * @param statusCode HTTP status from the response
     * @param message GitHub {@code message} field, or a fallback status line
     * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
     * @param rawBody response body as text, or {@code null}
     * @param headers response headers
     * @param method HTTP method, or {@code null}
     * @param uri last-hop request URI, or {@code null}
     */
    public GitHubUnauthorizedException(
            final int statusCode,
            final String message,
            final @Nullable String documentationUrl,
            final @Nullable String rawBody,
            final HttpHeaders headers,
            final @Nullable HttpMethod method,
            final @Nullable URI uri) {
        super(statusCode, message, documentationUrl, rawBody, headers, method, uri);
    }
}
