package io.github.piotr_janczukowicz.github.client.exception;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import java.io.Serial;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * The resource does not exist (HTTP 404) or is not visible to the caller.
 *
 * <p>GitHub also answers 404 for a private repository or organization the credential cannot access, so a 404 does not
 * prove that the resource is missing: check the token's scopes and access before concluding that it does not exist.
 */
public final class GitHubNotFoundException extends GitHubApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a 404 failure with request metadata.
     *
     * @param statusCode HTTP status from the response
     * @param message GitHub {@code message} field, or a fallback status line
     * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
     * @param rawBody response body as text, or {@code null}
     * @param headers response headers
     * @param method HTTP method, or {@code null}
     * @param uri last-hop request URI, or {@code null}
     */
    public GitHubNotFoundException(
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
