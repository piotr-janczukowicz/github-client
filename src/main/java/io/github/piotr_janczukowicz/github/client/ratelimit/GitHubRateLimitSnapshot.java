package io.github.piotr_janczukowicz.github.client.ratelimit;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Quota of one GitHub rate-limit resource as reported by the {@code X-RateLimit-*} headers of a response.
 *
 * <p>GitHub sends these headers on successful responses as well as on rejected ones, so a client can see how much of a
 * budget is left without spending a request on {@code GET /rate_limit}. A snapshot is taken at {@link #observedAt()}
 * and only describes the quota at that moment.
 *
 * @param resource rate-limit resource such as {@code core}, {@code search} or {@code graphql}
 * @param limit requests allowed in the current window
 * @param remaining requests left in the current window
 * @param used requests spent in the current window
 * @param resetAt when the window resets
 * @param observedAt when the response carrying these values was received
 */
public record GitHubRateLimitSnapshot(
        String resource, int limit, int remaining, int used, Instant resetAt, Instant observedAt) {

    /**
     * Resource of a response that carries quota headers but no {@code X-RateLimit-Resource}. It is a resource of its
     * own, so that such a response never overwrites the quota of {@code core}.
     */
    public static final String UNKNOWN_RESOURCE = "unknown";

    public GitHubRateLimitSnapshot {
        requireNotBlank(resource, "resource");
        requireAtLeast(limit, 0, "limit");
        requireAtLeast(remaining, 0, "remaining");
        requireAtLeast(used, 0, "used");
        requireNonNull(resetAt, "resetAt");
        requireNonNull(observedAt, "observedAt");
    }

    /**
     * Reads the quota from response headers.
     *
     * <p>Present when {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining} and {@code X-RateLimit-Reset} are all
     * valid numbers. A missing {@code X-RateLimit-Used} is taken as {@code limit - remaining}, and a missing or blank
     * {@code X-RateLimit-Resource} as {@value #UNKNOWN_RESOURCE}. The numbers are what this response reported: GitHub
     * may serve a later response of the same window from another region with a higher {@code remaining}, so do not
     * depend on exact counts.
     *
     * @param headers response headers
     * @param observedAt when the response was received
     * @return the snapshot, or empty when the response carries no usable quota
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Optional<GitHubRateLimitSnapshot> from(final HttpHeaders headers, final Instant observedAt) {
        requireNonNull(headers, "headers");
        requireNonNull(observedAt, "observedAt");
        final Integer limit = integer(headers, GitHubHeaders.RATE_LIMIT_LIMIT);
        final Integer remaining = integer(headers, GitHubHeaders.RATE_LIMIT_REMAINING);
        final Instant resetAt = instant(headers, GitHubHeaders.RATE_LIMIT_RESET);
        if (limit == null || remaining == null || resetAt == null || limit < 0 || remaining < 0) {
            return Optional.empty();
        }
        final Integer reported = integer(headers, GitHubHeaders.RATE_LIMIT_USED);
        final int used = reported != null && reported >= 0 ? reported : Math.max(0, limit - remaining);
        final String resource = headers.get(GitHubHeaders.RATE_LIMIT_RESOURCE)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(UNKNOWN_RESOURCE);
        return Optional.of(new GitHubRateLimitSnapshot(resource, limit, remaining, used, resetAt, observedAt));
    }

    /**
     * Whether no request is left in the current window.
     *
     * @return {@code true} when {@link #remaining()} is {@code 0}
     */
    public boolean isExhausted() {
        return remaining == 0;
    }

    /**
     * Time left until the window resets, never negative.
     *
     * <p>{@link #resetAt()} is on GitHub's clock and {@code now} is on yours, so the result is exact only when the two
     * agree. This snapshot keeps no server date to correct for a difference; for a wait that is corrected for it, use
     * {@link GitHubRateLimitInfo#waitFrom(Instant)} on a rate-limit response.
     *
     * @param now current time
     * @return duration until {@link #resetAt()}, or {@link Duration#ZERO} when it has passed
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public Duration untilReset(final Instant now) {
        requireNonNull(now, "now");
        final Duration left = Duration.between(now, resetAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static @Nullable Integer integer(final HttpHeaders headers, final String name) {
        return headers.get(name).map(GitHubRateLimitClassifiers::integer).orElse(null);
    }

    private static @Nullable Instant instant(final HttpHeaders headers, final String name) {
        return headers.get(name).map(GitHubRateLimitClassifiers::epochSecond).orElse(null);
    }
}
