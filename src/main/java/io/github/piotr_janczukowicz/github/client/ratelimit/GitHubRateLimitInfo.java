package io.github.piotr_janczukowicz.github.client.ratelimit;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import java.io.Serializable;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * GitHub rate-limit metadata taken from HTTP response headers and status.
 *
 * <p>Only HTTP 429 and 403 are rate limits. The message of the error body is read first: one that names a secondary
 * rate limit ({@code message} contains "secondary rate limit" or the older "abuse detection", or
 * {@code documentation_url} points at {@code secondary-rate-limit} or {@code abuse-rate-limit}) makes the response
 * {@link Kind#SECONDARY} even if {@code X-RateLimit-Remaining} is {@code 0}. Otherwise {@code X-RateLimit-Remaining: 0}
 * makes it {@link Kind#PRIMARY}, whatever else the body says: "Resource not accessible by integration" with remaining
 * {@code 0} is a spent quota. What is left is secondary for every 429, and for a 403 that has a positive
 * {@code Retry-After} together with an {@code X-RateLimit-Limit} or {@code X-RateLimit-Remaining} header. GitHub sends
 * those headers on every REST response, so the pair is its own signal, while a {@code Retry-After} alone, as a proxy or
 * CDN might add to a 403, is not. Any other 403, such as a permission error with quota left, is not a rate limit. A
 * body of at most 8 KiB that is a GitHub error object is judged by its fields; any other body is searched for the
 * phrases in its first 8 KiB.
 *
 * <p><strong>HEAD.</strong> A response to HEAD has no body, so its message cannot be read. A HEAD 403 is therefore
 * recognised only by {@code X-RateLimit-Remaining: 0} or by a {@code Retry-After} with rate-limit headers; a HEAD 403
 * with quota left and no {@code Retry-After} cannot be told from a permission error and is not treated as a rate limit.
 *
 * <p>{@code retryAfter} is the positive delay of {@code Retry-After}, or {@code null}: a zero, negative or unparsable
 * value is absent. {@link #retryAt()} says when the docs allow the next request. The values are as the response
 * reported them: do not depend on an exact {@code remaining}, because GitHub may report a higher count on a later
 * response of the same window.
 *
 * @param limit {@code X-RateLimit-Limit}, or {@code null}
 * @param remaining {@code X-RateLimit-Remaining}, or {@code null}
 * @param used {@code X-RateLimit-Used}, or {@code null}
 * @param resetAt {@code X-RateLimit-Reset} as an instant, or {@code null}
 * @param resource {@code X-RateLimit-Resource}, or {@code null}
 * @param retryAfter {@code Retry-After} as a positive duration, or {@code null}
 * @param kind primary vs secondary classification
 * @param serverDate the {@code Date} header: GitHub's clock when it answered, or {@code null}
 * @param observedAt when the response was received, on the local clock
 */
public record GitHubRateLimitInfo(
        @Nullable Integer limit,
        @Nullable Integer remaining,
        @Nullable Integer used,
        @Nullable Instant resetAt,
        @Nullable String resource,
        @Nullable Duration retryAfter,
        Kind kind,
        @Nullable Instant serverDate,
        Instant observedAt)
        implements Serializable {

    /** How long a secondary limit that names no wait asks to be waited out, as GitHub's docs require at least. */
    public static final Duration DEFAULT_SECONDARY_WAIT = Duration.ofSeconds(60);

    public GitHubRateLimitInfo {
        requireNonNull(kind, "kind");
        requireNonNull(observedAt, "observedAt");
    }

    /**
     * Parses rate-limit headers and classification from {@code response}.
     *
     * @param response HTTP response
     * @param clock clock that says when the response was received, and measures an HTTP-date {@code Retry-After} in a
     *     response without a {@code Date} header
     * @return present when the response signaled a rate limit
     */
    public static Optional<GitHubRateLimitInfo> from(final HttpResponse response, final Clock clock) {
        requireNonNull(response, "response");
        requireNonNull(clock, "clock");

        return GitHubRateLimitClassifiers.classify(response, clock)
                .map(kind -> new GitHubRateLimitInfo(
                        response.header(GitHubHeaders.RATE_LIMIT_LIMIT)
                                .map(GitHubRateLimitClassifiers::integer)
                                .orElse(null),
                        response.header(GitHubHeaders.RATE_LIMIT_REMAINING)
                                .map(GitHubRateLimitClassifiers::integer)
                                .orElse(null),
                        response.header(GitHubHeaders.RATE_LIMIT_USED)
                                .map(GitHubRateLimitClassifiers::integer)
                                .orElse(null),
                        response.header(GitHubHeaders.RATE_LIMIT_RESET)
                                .map(GitHubRateLimitClassifiers::epochSecond)
                                .orElse(null),
                        response.header(GitHubHeaders.RATE_LIMIT_RESOURCE).orElse(null),
                        GitHubRateLimitClassifiers.retryAfter(response, clock).orElse(null),
                        kind,
                        response.serverDate().orElse(null),
                        clock.instant()));
    }

    /**
     * The earliest instant, on the local clock, at which GitHub's documentation allows the next request. It is the
     * <em>minimum</em> wait: the retry layer adds a safety margin, jitter, and for repeated secondary limits an
     * exponentially longer wait.
     *
     * <p>A timestamp from GitHub is moved to the local clock by the difference between {@link #observedAt()} and
     * {@link #serverDate()} (none when there is no {@code Date}). A primary limit waits for the later of the reset and
     * {@code Retry-After}, and a minute when it has neither. A secondary limit waits {@code Retry-After}, else the
     * reset when {@code X-RateLimit-Remaining} is {@code 0}, else {@link #DEFAULT_SECONDARY_WAIT}.
     *
     * @return the instant on the local clock
     */
    public Instant retryAt() {
        final Instant afterRetryAfter = retryAfter == null ? null : plus(observedAt, retryAfter);
        final Instant localReset = resetAt == null ? null : plus(resetAt, skew());
        if (kind == Kind.PRIMARY) {
            return later(localReset, afterRetryAfter);
        }
        if (afterRetryAfter != null) {
            return afterRetryAfter;
        }
        if (localReset != null && remaining != null && remaining == 0) {
            return localReset;
        }
        return plus(observedAt, DEFAULT_SECONDARY_WAIT);
    }

    /**
     * Time from {@code now} until {@link #retryAt()}.
     *
     * @param now current time on the local clock
     * @return the wait, {@link Duration#ZERO} when {@link #retryAt()} has passed
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public Duration waitFrom(final Instant now) {
        requireNonNull(now, "now");
        final Duration wait = Duration.between(now, retryAt());
        return wait.isNegative() ? Duration.ZERO : wait;
    }

    /**
     * One-line summary for messages: the kind, the resource when known, and when to retry, for example {@code secondary
     * rate limit; resource core; retry at 2026-10-09T12:01:00Z, in 60 s}. The wait is counted from
     * {@link #observedAt()}.
     *
     * @return description without surrounding brackets
     */
    public String describe() {
        final String resourcePart = resource == null ? "" : "; resource " + resource;
        return (kind == Kind.PRIMARY ? "primary" : "secondary") + " rate limit" + resourcePart + "; retry at "
                + retryAt() + ", in " + waitFrom(observedAt).toSeconds() + " s";
    }

    // The later of two optional instants; a minute from now when there are none.
    private Instant later(final @Nullable Instant first, final @Nullable Instant second) {
        if (first == null && second == null) {
            return plus(observedAt, DEFAULT_SECONDARY_WAIT);
        }
        if (first == null || second != null && second.isAfter(first)) {
            return second;
        }
        return first;
    }

    private Duration skew() {
        return serverDate == null ? Duration.ZERO : Duration.between(serverDate, observedAt);
    }

    private static Instant plus(final Instant instant, final Duration amount) {
        try {
            return instant.plus(amount);
        } catch (final DateTimeException | ArithmeticException unbounded) {
            return Instant.MAX;
        }
    }

    /** Rate-limit class: remaining quota exhausted versus retry/backoff. */
    public enum Kind {
        PRIMARY,
        SECONDARY
    }
}
