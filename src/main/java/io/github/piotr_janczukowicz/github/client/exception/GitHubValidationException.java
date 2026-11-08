package io.github.piotr_janczukowicz.github.client.exception;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import java.io.Serial;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * GitHub refused the request because the payload failed validation.
 *
 * <p>Typically HTTP 422. {@link #errors()} is GitHub's {@code errors} array; it may be empty when the body omitted it
 * or could not be parsed.
 */
public final class GitHubValidationException extends GitHubApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final List<GitHubValidationError> errors;

    /**
     * Creates a validation failure for {@code statusCode} with headers, method, and request URI.
     *
     * @param statusCode HTTP status from the response
     * @param message GitHub {@code message} field, or a fallback status line
     * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
     * @param rawBody response body as text, or {@code null}
     * @param errors GitHub {@code errors} items; stored as an unmodifiable copy
     * @param headers response headers
     * @param method HTTP method of the request, or {@code null}
     * @param uri request URI, or {@code null}
     * @throws NullPointerException if {@code errors} is {@code null} or contains {@code null}
     */
    public GitHubValidationException(
            final int statusCode,
            final String message,
            final @Nullable String documentationUrl,
            final @Nullable String rawBody,
            final List<GitHubValidationError> errors,
            final HttpHeaders headers,
            final @Nullable HttpMethod method,
            final @Nullable URI uri) {
        super(statusCode, message, documentationUrl, rawBody, headers, method, uri);
        this.errors = List.copyOf(errors);
    }

    /**
     * GitHub {@code errors} array from the response body.
     *
     * @return unmodifiable list, possibly empty
     */
    public List<GitHubValidationError> errors() {
        return errors;
    }
}
