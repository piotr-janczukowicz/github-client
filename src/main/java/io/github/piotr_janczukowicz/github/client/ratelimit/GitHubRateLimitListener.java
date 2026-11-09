package io.github.piotr_janczukowicz.github.client.ratelimit;

/**
 * Receives the rate-limit quota that GitHub reports on a response, for metrics, logging or client-side throttling.
 *
 * <p>Called on the thread that made the request, once for every response (success or error) that carries the
 * {@code X-RateLimit-*} headers, including the final response of a followed redirect chain. A response that the retry
 * layer waited out and sent again is not reported: the listener sees final responses only, and the rejections in
 * between are reported through {@link io.github.piotr_janczukowicz.http.transport.RateLimitEventListener}. A response
 * that the retry layer replayed from a limit it remembered, without sending a request, is not reported either: it
 * repeats an earlier response and says nothing new about the quota. A client shared by many threads calls it
 * concurrently, so an implementation must be thread-safe and quick; it should hand slow work to another thread. An
 * exception thrown by the listener is logged and otherwise ignored: it never fails the request.
 *
 * <p>Snapshots can arrive out of order when requests overlap, and GitHub may report a higher {@code remaining} on a
 * later response of the same window, because it serves from several regions. Treat each snapshot as what that response
 * said and do not depend on exact counts. {@code GitHubClient.lastRateLimit(String)} keeps the latest one received;
 * this callback sees every one as received.
 */
@FunctionalInterface
public interface GitHubRateLimitListener {

    /**
     * Called with the quota of one response.
     *
     * @param snapshot quota reported by the response
     */
    void onRateLimit(GitHubRateLimitSnapshot snapshot);
}
