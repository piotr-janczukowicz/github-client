package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtMostOne;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Cursor pagination token. At most one of {@code before} and {@code after} is set.
 *
 * @param before cursor for earlier items, or {@code null}; read it as {@link #beforeCursor()}
 * @param after cursor for later items, or {@code null}; read it as {@link #afterCursor()}
 * @param perPage explicit per-page size, or {@code null} to take it from the client configuration; read it as
 *     {@link #explicitPerPage()}
 */
public record GitHubCursorRequest(
        @Nullable String before,
        @Nullable String after,
        @Nullable Integer perPage) implements GitHubPageRequest {

    /** Query parameter name for the before cursor. */
    public static final String BEFORE_PARAM = "before";

    /** Query parameter name for the after cursor. */
    public static final String AFTER_PARAM = "after";

    private static final String PER_PAGE_PARAM = "perPage";

    public GitHubCursorRequest {
        requireAtMostOne(before, after, "before and after");
        if (before != null) {
            requireNotBlank(before, BEFORE_PARAM);
        }
        if (after != null) {
            requireNotBlank(after, AFTER_PARAM);
        }
        if (perPage != null) {
            requireAtLeast(perPage, 1, PER_PAGE_PARAM);
        }
    }

    /**
     * Cursor pagination without {@code before} or {@code after}; per-page size comes from client configuration at bind.
     *
     * @return cursor request
     */
    public static GitHubCursorRequest unpositioned() {
        return new GitHubCursorRequest(null, null, null);
    }

    /**
     * Cursor pagination without {@code before} or {@code after}.
     *
     * @param perPage items per page
     * @return cursor request
     * @throws IllegalArgumentException if {@code perPage} is invalid
     */
    public static GitHubCursorRequest of(final int perPage) {
        return new GitHubCursorRequest(null, null, perPage);
    }

    /**
     * Cursor pagination with {@code after}; per-page size comes from client configuration at bind.
     *
     * @param after cursor for later items
     * @return cursor request
     * @throws NullPointerException if {@code after} is {@code null}
     * @throws IllegalArgumentException if {@code after} is blank
     */
    public static GitHubCursorRequest after(final String after) {
        return new GitHubCursorRequest(null, requireNotBlank(after, AFTER_PARAM), null);
    }

    /**
     * Cursor pagination with {@code after}.
     *
     * @param after cursor for later items
     * @param perPage items per page
     * @return cursor request
     * @throws NullPointerException if {@code after} is {@code null}
     * @throws IllegalArgumentException if {@code after} or {@code perPage} is invalid
     */
    public static GitHubCursorRequest after(final String after, final int perPage) {
        return new GitHubCursorRequest(null, requireNotBlank(after, AFTER_PARAM), perPage);
    }

    /**
     * Cursor pagination with {@code before}; per-page size comes from client configuration at bind.
     *
     * @param before cursor for earlier items
     * @return cursor request
     * @throws NullPointerException if {@code before} is {@code null}
     * @throws IllegalArgumentException if {@code before} is blank
     */
    public static GitHubCursorRequest before(final String before) {
        return new GitHubCursorRequest(requireNotBlank(before, BEFORE_PARAM), null, null);
    }

    /**
     * Cursor pagination with {@code before}.
     *
     * @param before cursor for earlier items
     * @param perPage items per page
     * @return cursor request
     * @throws NullPointerException if {@code before} is {@code null}
     * @throws IllegalArgumentException if {@code before} or {@code perPage} is invalid
     */
    public static GitHubCursorRequest before(final String before, final int perPage) {
        return new GitHubCursorRequest(requireNotBlank(before, BEFORE_PARAM), null, perPage);
    }

    /**
     * Cursor for earlier items.
     *
     * @return {@code before}, or empty
     */
    public Optional<String> beforeCursor() {
        return Optional.ofNullable(before);
    }

    /**
     * Cursor for later items.
     *
     * @return {@code after}, or empty
     */
    public Optional<String> afterCursor() {
        return Optional.ofNullable(after);
    }

    @Override
    public OptionalInt explicitPerPage() {
        return perPage == null ? OptionalInt.empty() : OptionalInt.of(perPage);
    }

    /**
     * Writes {@code before} and/or {@code after} when present.
     *
     * @param query mutable query map
     */
    @Override
    public void putQueryParameters(final Map<String, String> query) {
        if (before != null) {
            query.put(BEFORE_PARAM, before);
        }
        if (after != null) {
            query.put(AFTER_PARAM, after);
        }
    }
}
