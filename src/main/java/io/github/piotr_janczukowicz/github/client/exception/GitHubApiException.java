package io.github.piotr_janczukowicz.github.client.exception;

import static io.github.piotr_janczukowicz.util.StringUtils.isBlank;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.http.HttpErrorBodies;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.client.HttpException;
import java.io.Serial;
import java.net.URI;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Unsuccessful GitHub REST response.
 *
 * <p>It is an {@link HttpException}, so code written against the generic HTTP client can catch it as such. Subtypes
 * cover 401 ({@link GitHubUnauthorizedException}), 403 ({@link GitHubForbiddenException}), 404
 * ({@link GitHubNotFoundException}), rate limiting ({@link GitHubRateLimitException}), and validation
 * ({@link GitHubValidationException}). Other HTTP errors, including 304 when not listed in {@code expectedStatuses},
 * use this type directly. Wire failures stay as {@link java.io.IOException}.
 *
 * <p>The message is cut to 1024 characters (like {@link HttpException}) because it may come from a response body;
 * {@link #rawBody()} keeps up to {@value HttpErrorBodies#MAX_RAW_BODY_CHARS} characters of it, which is far more than
 * GitHub's error bodies need.
 */
public sealed class GitHubApiException extends HttpException
        permits GitHubForbiddenException,
                GitHubNotFoundException,
                GitHubRateLimitException,
                GitHubUnauthorizedException,
                GitHubValidationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable String documentationUrl;
    // Kept apart from the transient headers: a correlation id is not sensitive and is what GitHub support asks for.
    private final @Nullable String requestId;

    /**
     * Creates an exception for {@code statusCode} with GitHub's {@code message}.
     *
     * @param statusCode HTTP status from the response
     * @param message GitHub {@code message} field, or a fallback status line
     * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
     * @param rawBody response body as text, or {@code null}
     */
    public GitHubApiException(
            final int statusCode,
            final String message,
            final @Nullable String documentationUrl,
            final @Nullable String rawBody) {
        this(statusCode, message, documentationUrl, rawBody, HttpHeaders.empty(), null, null);
    }

    /**
     * Creates an exception with request and response metadata.
     *
     * @param statusCode HTTP status from the response
     * @param message GitHub {@code message} field, or a fallback status line
     * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
     * @param rawBody response body as text, or {@code null}
     * @param headers response headers
     * @param method request method, or {@code null}
     * @param uri request URI, or {@code null}
     */
    public GitHubApiException(
            final int statusCode,
            final String message,
            final @Nullable String documentationUrl,
            final @Nullable String rawBody,
            final @Nullable HttpHeaders headers,
            final @Nullable HttpMethod method,
            final @Nullable URI uri) {
        super(statusCode, HttpErrorBodies.truncated(message), rawBody, headers, method, uri);
        this.documentationUrl = blankToNull(documentationUrl);
        this.requestId = requestIdOf(headers);
    }

    /**
     * GitHub {@code documentation_url} when the body included a non-blank value.
     *
     * @return documentation URL, or empty
     */
    public Optional<String> documentationUrl() {
        return Optional.ofNullable(documentationUrl);
    }

    /**
     * {@code X-GitHub-Request-Id} when present. Unlike {@link #headers()}, it survives Java serialization.
     *
     * @return request id, or empty
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    private static @Nullable String requestIdOf(final @Nullable HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        return headers.get(GitHubHeaders.REQUEST_ID).orElse(null);
    }

    private static @Nullable String blankToNull(final @Nullable String value) {
        return isBlank(value) ? null : value;
    }
}
