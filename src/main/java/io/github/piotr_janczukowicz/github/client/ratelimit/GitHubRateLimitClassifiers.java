package io.github.piotr_janczukowicz.github.client.ratelimit;

import static io.github.piotr_janczukowicz.util.ValidationUtils.isNonPositive;

import io.github.piotr_janczukowicz.github.GitHubErrorBody;
import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.github.json.GitHubJsonCodecs;
import io.github.piotr_janczukowicz.http.HttpErrorBodies;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** GitHub's rate-limit wire format: header names, number parsing, and the primary/secondary classification. */
final class GitHubRateLimitClassifiers {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubRateLimitClassifiers.class);

    private static final String SECONDARY_MESSAGE = "secondary rate limit";
    private static final String ABUSE_MESSAGE = "abuse detection";
    private static final String SECONDARY_DOCUMENTATION = "secondary-rate-limit";
    private static final String ABUSE_DOCUMENTATION = "abuse-rate-limit";

    private GitHubRateLimitClassifiers() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Parses a header value as an integer.
     *
     * @param value header value, surrounding whitespace allowed
     * @return the number, or {@code null} when {@code value} is not an integer
     */
    static @Nullable Integer integer(final String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (final NumberFormatException unused) {
            // A malformed header is treated as absent: quota headers are advisory, and a bad one must not fail a call.
            return null;
        }
    }

    /**
     * Parses a header value as seconds since the epoch.
     *
     * @param value header value, surrounding whitespace allowed
     * @return the instant, or {@code null} when {@code value} is not a valid epoch second
     */
    static @Nullable Instant epochSecond(final String value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(value.trim()));
        } catch (final NumberFormatException | DateTimeException unused) {
            // Advisory header, see integer(String).
            return null;
        }
    }

    /**
     * Classifies {@code response} from its status, its error message and its headers. Only HTTP 429 and 403 can be a
     * rate limit.
     *
     * <p>A body that names a secondary limit makes the response {@link GitHubRateLimitInfo.Kind#SECONDARY}, whatever
     * the headers say, and otherwise {@code X-RateLimit-Remaining: 0} makes it
     * {@link GitHubRateLimitInfo.Kind#PRIMARY}. A 429 that is neither is secondary. A 403 that is neither is a rate
     * limit only when it carries a positive {@code Retry-After} together with {@code X-RateLimit-*} headers, which
     * GitHub sends on every REST response; a {@code Retry-After} alone, as a proxy might add, is not evidence of a rate
     * limit.
     *
     * @param response HTTP response
     * @param clock clock for an HTTP-date {@code Retry-After} in a response without a {@code Date}
     * @return the kind of limit, or empty when the response is not a rate-limit response
     */
    static Optional<GitHubRateLimitInfo.Kind> classify(final HttpResponse response, final Clock clock) {
        final boolean isTooManyRequests = response.hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        if (!isTooManyRequests && !response.hasStatus(HttpStatus.FORBIDDEN)) {
            return Optional.empty();
        }

        if (isSecondaryMessage(response)) {
            return classified(response, GitHubRateLimitInfo.Kind.SECONDARY, "the body names a secondary rate limit");
        }

        if (isRemainingZero(response)) {
            return classified(response, GitHubRateLimitInfo.Kind.PRIMARY, "X-RateLimit-Remaining is 0");
        }

        if (isTooManyRequests) {
            return classified(response, GitHubRateLimitInfo.Kind.SECONDARY, "HTTP 429 without a primary limit");
        }
        if (hasRetryAfter(response, clock) && hasRateLimitHeaders(response)) {
            return classified(
                    response,
                    GitHubRateLimitInfo.Kind.SECONDARY,
                    "HTTP 403 with a positive Retry-After and X-RateLimit headers");
        }

        LOG.atDebug()
                .setMessage("HTTP {} is not a rate limit: no secondary-limit text, remaining is not 0, and no"
                        + " Retry-After together with X-RateLimit headers")
                .addArgument(response::statusCode)
                .log();
        return Optional.empty();
    }

    /**
     * Whether the body of {@code response} says that a secondary rate limit was hit. A body of at most 8 KiB that is a
     * GitHub error object is judged by its {@code message} ("secondary rate limit", or the older "abuse detection") and
     * its {@code documentation_url} ({@code secondary-rate-limit}, {@code abuse-rate-limit}). Any other body is
     * searched for the two phrases in its first 8 KiB.
     *
     * @param response HTTP response
     * @return {@code true} when the body names a secondary limit
     */
    static boolean isSecondaryMessage(final HttpResponse response) {
        return response.mapBody(GitHubRateLimitClassifiers::isSecondaryBody, () -> false);
    }

    /**
     * Value of {@code X-RateLimit-Remaining} when it is zero. A value that is not a number is not zero.
     *
     * @param response HTTP response
     * @return {@code true} when remaining parses to {@code 0}
     */
    static boolean isRemainingZero(final HttpResponse response) {
        return response.header(GitHubHeaders.RATE_LIMIT_REMAINING)
                .map(GitHubRateLimitClassifiers::integer)
                .filter(value -> value == 0)
                .isPresent();
    }

    /**
     * {@code Retry-After} as a delay, when it is positive. A zero, negative or unparsable value is absent.
     *
     * @param response HTTP response
     * @param clock clock for an HTTP-date value in a response without a {@code Date}
     * @return the delay, or empty
     */
    static Optional<Duration> retryAfter(final HttpResponse response, final Clock clock) {
        return response.retryAfter(clock).filter(delay -> !isNonPositive(delay));
    }

    private static boolean hasRetryAfter(final HttpResponse response, final Clock clock) {
        return retryAfter(response, clock).isPresent();
    }

    private static boolean hasRateLimitHeaders(final HttpResponse response) {
        return response.header(GitHubHeaders.RATE_LIMIT_LIMIT).isPresent()
                || response.header(GitHubHeaders.RATE_LIMIT_REMAINING).isPresent();
    }

    private static boolean isSecondaryBody(final byte[] bytes) {
        if (bytes.length <= HttpErrorBodies.ERROR_BODY_SCAN_BYTES) {
            try {
                final GitHubErrorBody body = GitHubJsonCodecs.defaults().decode(bytes, GitHubErrorBody.class);
                return isSecondaryLimitMentioned(body.message())
                        || isSecondaryLimitDocumentation(body.documentationUrl());
            } catch (final RuntimeException unused) {
                // Not a GitHub error object (a proxy's page, say): the phrases are looked for in the raw text.
                LOG.atDebug()
                        .setMessage("A {} byte error body is not a GitHub error object ({}); scanning its text instead")
                        .addArgument(bytes.length)
                        .addArgument(unused.getClass().getSimpleName())
                        .log();
                return isSecondaryLimitMentioned(bytes);
            }
        }
        return isSecondaryLimitMentioned(bytes);
    }

    private static boolean isSecondaryLimitMentioned(final @Nullable String message) {
        if (message == null) {
            return false;
        }
        final String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(SECONDARY_MESSAGE) || lower.contains(ABUSE_MESSAGE);
    }

    private static boolean isSecondaryLimitMentioned(final byte[] bytes) {
        final String prefix = new String(
                        bytes, 0, Math.min(bytes.length, HttpErrorBodies.ERROR_BODY_SCAN_BYTES), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return prefix.contains(SECONDARY_MESSAGE) || prefix.contains(ABUSE_MESSAGE);
    }

    private static boolean isSecondaryLimitDocumentation(final @Nullable String documentationUrl) {
        return documentationUrl != null
                && (documentationUrl.contains(SECONDARY_DOCUMENTATION)
                        || documentationUrl.contains(ABUSE_DOCUMENTATION));
    }

    private static Optional<GitHubRateLimitInfo.Kind> classified(
            final HttpResponse response, final GitHubRateLimitInfo.Kind kind, final String reason) {
        LOG.atDebug()
                .setMessage("HTTP {} classified as a {} rate limit: {}")
                .addArgument(response::statusCode)
                .addArgument(kind)
                .addArgument(reason)
                .log();
        return Optional.of(kind);
    }
}
