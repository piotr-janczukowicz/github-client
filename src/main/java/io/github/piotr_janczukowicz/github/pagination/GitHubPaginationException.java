package io.github.piotr_janczukowicz.github.pagination;

import java.io.Serial;

/**
 * A page walk cannot continue because GitHub's pagination data is unusable, for example a {@code Link rel="next"}
 * target that is not a valid URI, a walk that would never end ({@link GitHubPaginationLoopException}), or a configured
 * page limit that was reached ({@link GitHubPaginationLimitExceededException}). Messages never include a query string.
 */
public class GitHubPaginationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param message what made the walk impossible to continue */
    public GitHubPaginationException(final String message) {
        super(message);
    }

    /**
     * @param message what made the walk impossible to continue
     * @param cause underlying failure
     */
    public GitHubPaginationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
