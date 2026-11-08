package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;

import java.util.Map;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Page-number pagination token.
 *
 * @param page 1-based page index
 * @param perPage explicit per-page size, or {@code null} to take it from the client configuration; read it as
 *     {@link #explicitPerPage()}
 */
public record GitHubPageNumberRequest(int page, @Nullable Integer perPage) implements GitHubPageRequest {

    /** Query parameter name for the 1-based page index. */
    public static final String PAGE_PARAM = "page";

    public GitHubPageNumberRequest {
        requireAtLeast(page, 1, PAGE_PARAM);
        if (perPage != null) {
            requireAtLeast(perPage, 1, "perPage");
        }
    }

    /**
     * Page {@code page} with no explicit {@code per_page}.
     *
     * @param page 1-based page index
     * @return page request
     * @throws IllegalArgumentException if {@code page} is invalid
     */
    public static GitHubPageNumberRequest of(final int page) {
        return new GitHubPageNumberRequest(page, null);
    }

    /**
     * Page {@code page} with explicit {@code perPage}.
     *
     * @param page 1-based page index
     * @param perPage items per page
     * @return page request
     * @throws IllegalArgumentException if {@code page} or {@code perPage} is invalid
     */
    public static GitHubPageNumberRequest of(final int page, final int perPage) {
        return new GitHubPageNumberRequest(page, perPage);
    }

    @Override
    public OptionalInt explicitPerPage() {
        return perPage == null ? OptionalInt.empty() : OptionalInt.of(perPage);
    }

    /**
     * Writes the 1-based {@code page} query parameter.
     *
     * @param query mutable query map
     */
    @Override
    public void putQueryParameters(final Map<String, String> query) {
        query.put(PAGE_PARAM, String.valueOf(page));
    }
}
