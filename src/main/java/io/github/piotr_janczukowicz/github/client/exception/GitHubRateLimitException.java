package io.github.piotr_janczukowicz.github.client.exception;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitInfo;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.transport.RateLimitGateHeaders;
import java.io.Serial;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * GitHub refused the request because the caller exceeded a rate limit.
 *
 * <p>It is HTTP 429 or 403, classified by
 * {@link GitHubRateLimitInfo#from(io.github.piotr_janczukowicz.http.HttpResponse, java.time.Clock)}: a secondary rate
 * limit when the error body says so ("secondary rate limit", the older "abuse detection", or a
 * {@code documentation_url} about secondary or abuse limits), a primary limit when {@code X-RateLimit-Remaining} is
 * {@code 0}, any other 429 (secondary), and a 403 with a positive {@code Retry-After} together with
 * {@code X-RateLimit-*} headers (secondary). A 403 that is only a permission denial is
 * {@link GitHubForbiddenException}, even with a {@code Retry-After} that no {@code X-RateLimit-*} header accompanies. A
 * rejected HEAD has no body, so a secondary limit on HEAD is recognised only by those headers; a HEAD 403 with quota
 * left and no {@code Retry-After} looks like a permission error and is reported as one.
 *
 * <p>The client may have waited out earlier rejections before this one; this exception is the last. When the retry
 * layer's coordination gate refused to send the request because an earlier response set a limit that is still in force,
 * the response behind this exception is that earlier rejection, without a {@code Date} and with {@code Retry-After} set
 * to the time left; no request reached GitHub. {@link #isClientSideBlock()} tells these two cases apart.
 *
 * <p>{@link #retryAt()} is the instant at which GitHub's documentation allows the next request, as computed by
 * {@link GitHubRateLimitInfo#retryAt()}. It is the minimum: a caller that retries should add a margin.
 */
public final class GitHubRateLimitException extends GitHubApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable GitHubRateLimitInfo rateLimit;

    /**
     * Creates a rate-limit failure for {@code statusCode} with parsed {@code rateLimit} metadata, headers, method, and
     * request URI.
     *
     * @param statusCode HTTP status from the response
     * @param message GitHub {@code message} field, or a fallback status line
     * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
     * @param rawBody response body as text, or {@code null}
     * @param rateLimit parsed rate-limit headers, or {@code null}
     * @param headers response headers
     * @param method HTTP method of the request, or {@code null}
     * @param uri request URI, or {@code null}
     */
    public GitHubRateLimitException(
            final int statusCode,
            final String message,
            final @Nullable String documentationUrl,
            final @Nullable String rawBody,
            final @Nullable GitHubRateLimitInfo rateLimit,
            final HttpHeaders headers,
            final @Nullable HttpMethod method,
            final @Nullable URI uri) {
        super(statusCode, message, documentationUrl, rawBody, headers, method, uri);
        this.rateLimit = rateLimit;
    }

    /**
     * Parsed rate-limit metadata when the mapper could classify the response.
     *
     * @return rate-limit info, or empty when the mapper could not classify headers
     */
    public Optional<GitHubRateLimitInfo> rateLimit() {
        return Optional.ofNullable(rateLimit);
    }

    /**
     * Earliest instant, on the local clock, at which GitHub's documentation allows the next request; see
     * {@link GitHubRateLimitInfo#retryAt()}.
     *
     * @return the instant, or empty when the mapper could not classify headers
     */
    public Optional<Instant> retryAt() {
        return rateLimit().map(GitHubRateLimitInfo::retryAt);
    }

    /**
     * Whether the limit is a primary (quota exhausted) or a secondary one.
     *
     * @return the kind, or empty when the mapper could not classify headers
     */
    public Optional<GitHubRateLimitInfo.Kind> kind() {
        return rateLimit().map(GitHubRateLimitInfo::kind);
    }

    /**
     * Whether the client refused to send the request because an earlier response reported a limit that is still in
     * force. The rate-limit data of this exception is then that of the earlier rejection, with the time left as
     * {@code Retry-After}, and no request reached GitHub.
     *
     * @return {@code true} when the response behind this exception was replayed by the client, {@code false} when
     *     GitHub sent it
     */
    public boolean isClientSideBlock() {
        return RateLimitGateHeaders.isPresent(headers());
    }

    /**
     * The rate-limit resource ({@code X-RateLimit-Resource}) that was exhausted, such as {@code core} or
     * {@code search}.
     *
     * @return the resource, or empty when it is unknown
     */
    public Optional<String> resource() {
        return rateLimit().map(GitHubRateLimitInfo::resource);
    }
}
