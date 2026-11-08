package io.github.piotr_janczukowicz.github.pagination;

import java.io.Serial;

/** A page walk would never end: a {@code Link rel="next"} URI repeated. */
public final class GitHubPaginationLoopException extends GitHubPaginationException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param message what repeated or failed to advance */
    public GitHubPaginationLoopException(final String message) {
        super(message);
    }
}
