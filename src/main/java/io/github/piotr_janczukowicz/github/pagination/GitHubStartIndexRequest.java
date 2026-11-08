package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireBetween;

import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * SCIM-style 1-based index pagination token.
 *
 * <p>The page size is sent as the {@code count} query parameter, not as {@code per_page}, so {@link #explicitPerPage()}
 * is always empty. The following page is not announced by a {@code Link} header: it is derived from the decoded body by
 * {@link #next(int, Integer)}, so a walk that starts with this token never reads {@code rel="next"}.
 *
 * @param startIndex 1-based index of the first result on this page
 * @param count page size from 1 to 100, or {@code null} to let the server choose. The upper bound is a limit of this
 *     library: the SCIM description declares no maximum
 */
public record GitHubStartIndexRequest(
        int startIndex, @Nullable Integer count) implements GitHubPageRequest {

    /** Query parameter name for the 1-based index of the first result. */
    public static final String START_INDEX_PARAM = "startIndex";

    /** Query parameter name for the page size. */
    public static final String COUNT_PARAM = "count";

    public GitHubStartIndexRequest {
        requireAtLeast(startIndex, 1, START_INDEX_PARAM);
        if (count != null) {
            requireBetween(count, 1, PaginationConfiguration.MAX_PER_PAGE, COUNT_PARAM);
        }
    }

    /**
     * First index page with no {@code count}.
     *
     * @return start index request for {@code startIndex=1}
     */
    public static GitHubStartIndexRequest first() {
        return new GitHubStartIndexRequest(1, null);
    }

    /**
     * Index page starting at {@code startIndex} with no {@code count}.
     *
     * @param startIndex 1-based index of the first result
     * @return start index request
     * @throws IllegalArgumentException if {@code startIndex} is less than 1
     */
    public static GitHubStartIndexRequest of(final int startIndex) {
        return new GitHubStartIndexRequest(startIndex, null);
    }

    /**
     * Index page starting at {@code startIndex} with an explicit page size.
     *
     * @param startIndex 1-based index of the first result
     * @param count items per page, from 1 to 100
     * @return start index request
     * @throws IllegalArgumentException if {@code startIndex} is less than 1 or {@code count} is outside 1 to 100
     */
    public static GitHubStartIndexRequest of(final int startIndex, final int count) {
        return new GitHubStartIndexRequest(startIndex, count);
    }

    /**
     * Token for the page that follows a page of {@code itemsReceived} items, or empty when that page lies beyond
     * {@code totalResults}. The next index is this index plus the items received, and it keeps this page's
     * {@code count}.
     *
     * @param itemsReceived number of items decoded from this page
     * @param totalResults total number of results from the body, or {@code null} when the body does not give one
     * @return the following page, or empty when no items were received, the total is unknown, or the next index is
     *     greater than {@code totalResults}
     */
    public Optional<GitHubStartIndexRequest> next(final int itemsReceived, final @Nullable Integer totalResults) {
        if (itemsReceived < 1 || totalResults == null) {
            return Optional.empty();
        }
        final long following = (long) startIndex + itemsReceived;
        if (following > totalResults) {
            return Optional.empty();
        }
        return Optional.of(new GitHubStartIndexRequest((int) following, count));
    }

    /**
     * Always empty: the page size of this token is the {@code count} parameter.
     *
     * @return empty
     */
    @Override
    public OptionalInt explicitPerPage() {
        return OptionalInt.empty();
    }

    /**
     * Writes {@code startIndex} and, when present, {@code count}.
     *
     * @param query mutable query map
     */
    @Override
    public void putQueryParameters(final Map<String, String> query) {
        query.put(START_INDEX_PARAM, String.valueOf(startIndex));
        if (count != null) {
            query.put(COUNT_PARAM, String.valueOf(count));
        }
    }
}
