package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.StringUtils.isBlank;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.GitHubErrorBody;
import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubForbiddenException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubNotFoundException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubUnauthorizedException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubValidationError;
import io.github.piotr_janczukowicz.github.client.exception.GitHubValidationException;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitInfo;
import io.github.piotr_janczukowicz.github.json.GitHubJsonCodecs;
import io.github.piotr_janczukowicz.http.HttpErrorBodies;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.json.JsonCodec;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maps an unsuccessful response to a {@link GitHubApiException} subtype. */
final class GitHubErrors {

    /** Largest error body that is parsed as JSON; GitHub error objects are a few hundred bytes. */
    static final int MAX_JSON_BYTES = 1_048_576;

    private static final Logger LOG = LoggerFactory.getLogger(GitHubErrors.class);

    private static final JsonCodec CODEC = GitHubJsonCodecs.defaults();

    private GitHubErrors() {
        throw new UnsupportedOperationException("Utility class");
    }

    static GitHubApiException toException(
            final HttpMethod method, final HttpResponse response, final URI requestUri, final Clock clock) {
        requireNonNull(method, "method");
        requireNonNull(response, "response");
        requireNonNull(requestUri, "requestUri");
        requireNonNull(clock, "clock");

        final ParsedError parsed = response.mapBody(
                bytes -> parse(bytes, response.statusCode(), response.charset()), () -> absent(response.statusCode()));

        final Failure failure = new Failure(response, parsed, method, requestUri);
        final Optional<GitHubRateLimitInfo> rateLimit = GitHubRateLimitInfo.from(response, clock);
        if (rateLimit.isPresent()) {
            return failure.rateLimited(rateLimit.orElseThrow());
        }

        return switch (response.statusCode()) {
            case 401 -> failure.unauthorized();
            case 404 -> failure.notFound();
            case 403 -> failure.forbidden(ssoUrl(response));
            case 422 -> failure.invalid();
            default -> failure.generic();
        };
    }

    /**
     * Result when the HTTP body is absent.
     *
     * @param statusCode HTTP status used for the fallback message
     * @return parsed error with no raw body and empty errors
     */
    static ParsedError absent(final int statusCode) {
        return new ParsedError(fallbackMessage(statusCode), null, List.of(), null);
    }

    /**
     * Decodes {@code bytes} as a GitHub error object. A body larger than {@link #MAX_JSON_BYTES} is not parsed.
     *
     * @param bytes response body bytes
     * @param statusCode HTTP status used for fallback messages
     * @param charset charset of the response, used for the raw body text (the JSON is always read as UTF-8)
     * @return parsed error (always includes an errors list, possibly empty)
     * @throws NullPointerException if an argument is {@code null}
     */
    static ParsedError parse(final byte[] bytes, final int statusCode, final Charset charset) {
        requireNonNull(bytes, "bytes");
        requireNonNull(charset, "charset");

        final String rawBody = HttpErrorBodies.decodeBounded(bytes, charset);
        // An empty body has nothing to parse, and a huge one is not a GitHub error object (a proxy page, say): parsing
        // it would only build a large object graph, so both get the status message and the (capped) raw text.
        if (bytes.length == 0 || bytes.length > MAX_JSON_BYTES) {
            LOG.atDebug()
                    .setMessage("HTTP {} error body of {} bytes is not parsed as a GitHub error object")
                    .addArgument(statusCode)
                    .addArgument(bytes.length)
                    .log();
            return new ParsedError(fallbackMessage(statusCode), null, List.of(), rawBody);
        }
        try {
            final GitHubErrorBody body = CODEC.decode(bytes, GitHubErrorBody.class);
            return fromBody(body, statusCode, rawBody);
        } catch (final RuntimeException unused) {
            // A body that is not a GitHub error object is reported as the status message plus the raw text.
            LOG.atDebug()
                    .setMessage("HTTP {} error body of {} bytes is not a GitHub error object ({}); using the status")
                    .addArgument(statusCode)
                    .addArgument(bytes.length)
                    .addArgument(unused.getClass().getName())
                    .log();
            return new ParsedError(fallbackMessage(statusCode), null, List.of(), rawBody);
        }
    }

    // A JsonCodec never returns null (a JSON null fails with an IllegalStateException), so body is always a value.
    private static ParsedError fromBody(final GitHubErrorBody body, final int statusCode, final String rawBody) {
        final String jsonMessage = body.message();
        final String message = isBlank(jsonMessage) ? fallbackMessage(statusCode) : jsonMessage;
        return new ParsedError(message, body.documentationUrl(), toValidationErrors(body.errors()), rawBody);
    }

    private static List<GitHubValidationError> toValidationErrors(final List<GitHubErrorBody.Item> items) {
        return items.stream().map(GitHubErrors::toValidationError).toList();
    }

    private static GitHubValidationError toValidationError(final GitHubErrorBody.Item item) {
        return new GitHubValidationError(item.resource(), item.field(), item.code(), item.message());
    }

    private static String fallbackMessage(final int statusCode) {
        return "GitHub API request failed with HTTP " + statusCode;
    }

    private static @Nullable String ssoUrl(final HttpResponse response) {
        return GitHubSsoHeaders.authorizationUrl(response.headers()).orElse(null);
    }

    /** Decoded GitHub error body. */
    record ParsedError(
            String message,
            @Nullable String documentationUrl,
            List<GitHubValidationError> errors,
            @Nullable String rawBody) {

        ParsedError {
            requireNonNull(message, "message");
            errors = List.copyOf(requireNonNull(errors, "errors"));
        }
    }

    /** One unsuccessful response, with what every {@link GitHubApiException} subtype is built from. */
    private record Failure(HttpResponse response, ParsedError parsed, HttpMethod method, URI uri) {

        GitHubApiException generic() {
            return new GitHubApiException(
                    response.statusCode(),
                    parsed.message(),
                    parsed.documentationUrl(),
                    parsed.rawBody(),
                    response.headers(),
                    method,
                    uri);
        }

        GitHubApiException rateLimited(final GitHubRateLimitInfo rateLimit) {
            return new GitHubRateLimitException(
                    response.statusCode(),
                    parsed.message() + " [" + rateLimit.describe() + "]",
                    parsed.documentationUrl(),
                    parsed.rawBody(),
                    rateLimit,
                    response.headers(),
                    method,
                    uri);
        }

        GitHubApiException unauthorized() {
            return new GitHubUnauthorizedException(
                    response.statusCode(),
                    parsed.message(),
                    parsed.documentationUrl(),
                    parsed.rawBody(),
                    response.headers(),
                    method,
                    uri);
        }

        GitHubApiException notFound() {
            return new GitHubNotFoundException(
                    response.statusCode(),
                    parsed.message(),
                    parsed.documentationUrl(),
                    parsed.rawBody(),
                    response.headers(),
                    method,
                    uri);
        }

        GitHubApiException forbidden(final @Nullable String ssoUrl) {
            return new GitHubForbiddenException(
                    response.statusCode(),
                    parsed.message(),
                    parsed.documentationUrl(),
                    parsed.rawBody(),
                    ssoUrl,
                    response.headers(),
                    method,
                    uri);
        }

        GitHubApiException invalid() {
            return new GitHubValidationException(
                    response.statusCode(),
                    parsed.message(),
                    parsed.documentationUrl(),
                    parsed.rawBody(),
                    parsed.errors(),
                    response.headers(),
                    method,
                    uri);
        }
    }
}
