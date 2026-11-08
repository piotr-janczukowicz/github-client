package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.client.HttpErrorMapper;
import java.net.URI;
import java.time.Clock;
import org.jspecify.annotations.Nullable;

/**
 * Maps the error responses of GitHub to {@link GitHubApiException}s, see {@link GitHubErrors}. It uses {@code clock},
 * the same clock that stamps the rate-limit quota of the client, and parses error bodies with the default JSON codec.
 */
final class GitHubErrorMapper implements HttpErrorMapper {

    private final Clock clock;

    /**
     * Mapper that reads time from {@code clock}.
     *
     * @param clock time source of the rate-limit decisions
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    GitHubErrorMapper(final Clock clock) {
        this.clock = requireNonNull(clock, "clock");
    }

    @Override
    public RuntimeException map(final HttpMethod method, final HttpResponse response, final URI requestUri) {
        return GitHubErrors.toException(method, response, requestUri, clock);
    }

    @Override
    public RuntimeException redirectFailure(
            final HttpResponse response,
            final String message,
            final @Nullable String rawBody,
            final @Nullable HttpMethod method,
            final URI uri) {
        return new GitHubApiException(
                response.statusCode(),
                message,
                null,
                rawBody,
                response.headers().without(LOCATION),
                method,
                uri);
    }
}
