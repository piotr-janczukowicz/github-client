package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.io.Serial;
import java.time.Instant;

/**
 * An installation token cannot be had right now because an earlier fetch failed and
 * {@link GitHubInstallationAuthenticationProvider} is still inside its failure backoff, so it made no new request.
 *
 * <p>The {@linkplain #getCause() cause} is the failure of the fetch that started the backoff: an
 * {@link java.io.UncheckedIOException} for a transport failure, or a
 * {@link io.github.piotr_janczukowicz.github.client.exception.GitHubApiException} when GitHub rejected the token
 * request. It is shared by every thread that gets this exception, so do not modify it. The thread that made the failed
 * request gets the original exception, not this one. The message names the retry time and carries no token and no
 * response content.
 */
public final class GitHubInstallationTokenUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Instant retryAt;

    /**
     * Creates the failure for a token fetch that is held back until {@code retryAt}.
     *
     * @param retryAt when {@link GitHubInstallationAuthenticationProvider#authentication()} makes a new request again
     * @param cause failure of the fetch that started the backoff
     * @throws NullPointerException if an argument is {@code null}
     */
    public GitHubInstallationTokenUnavailableException(final Instant retryAt, final RuntimeException cause) {
        super(
                "an earlier installation token fetch failed; no new request before "
                        + requireNonNull(retryAt, "retryAt"),
                requireNonNull(cause, "cause"));
        this.retryAt = retryAt;
    }

    /**
     * When the auth makes its next token request.
     *
     * @return the end of the failure backoff
     */
    public Instant retryAt() {
        return retryAt;
    }
}
