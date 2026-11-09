package io.github.piotr_janczukowicz.github.pagination;

import java.io.Serial;

/** A configured {@code maxPages} was reached while GitHub still offered another page. */
public final class GitHubPaginationLimitExceededException extends GitHubPaginationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int maxPages;
    private final int pageCount;

    /**
     * @param maxPages configured page-follow cap
     * @param pageCount number of HTTP pages already fetched
     */
    public GitHubPaginationLimitExceededException(final int maxPages, final int pageCount) {
        super("pagination exceeded maxPages (" + maxPages + ") after " + pageCount + " page(s)");
        this.maxPages = maxPages;
        this.pageCount = pageCount;
    }

    /**
     * Configured inclusive page-follow cap.
     *
     * @return max pages
     */
    public int maxPages() {
        return maxPages;
    }

    /**
     * HTTP pages fetched when the cap stopped the walk.
     *
     * @return page count
     */
    public int pageCount() {
        return pageCount;
    }
}
