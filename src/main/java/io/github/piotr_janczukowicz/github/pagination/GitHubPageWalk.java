package io.github.piotr_janczukowicz.github.pagination;

import java.io.IOException;
import java.util.Optional;

/**
 * HTTP walk for one {@link GitHubPage}: next page and latest-page checks.
 *
 * @param <T> item type of pages in this walk
 */
public interface GitHubPageWalk<T> {

    /**
     * Next page in this walk when {@code current} is the latest page and a next link exists.
     *
     * @param current latest page of this walk
     * @return next page, or empty
     * @throws IOException if the transport fails
     */
    Optional<GitHubPage<T>> loadNext(GitHubPage<T> current) throws IOException;

    /**
     * Ensures {@code page} is the latest page of this walk.
     *
     * @param page page to check
     * @throws IllegalStateException if {@code page} is not the latest
     */
    void requireLatest(GitHubPage<T> page);
}
