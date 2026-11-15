package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.http.client.HttpResult;
import io.github.piotr_janczukowicz.util.NumberUtils;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/** Reads the interval that GitHub advises between polls of a resource. */
public final class GitHubPollInterval {

    private GitHubPollInterval() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Interval that the server advertises for polls of this resource in the {@code X-Poll-Interval} header, which
     * counts whole seconds. A client that polls should wait at least this long.
     *
     * @param result response to read
     * @return the interval, zero or more seconds, or empty when the header is missing, is not a whole number of
     *     seconds, or is negative
     * @throws NullPointerException if {@code result} is {@code null}
     */
    public static Optional<Duration> of(final HttpResult<?> result) {
        requireNonNull(result, "result");
        return result.response().header(GitHubHeaders.POLL_INTERVAL).flatMap(GitHubPollInterval::wholeSeconds);
    }

    private static Optional<Duration> wholeSeconds(final String value) {
        final OptionalLong seconds = NumberUtils.parseLong(value);
        return seconds.isEmpty() || seconds.getAsLong() < 0
                ? Optional.empty()
                : Optional.of(Duration.ofSeconds(seconds.getAsLong()));
    }
}
