package io.github.piotr_janczukowicz.github.pagination;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Pagination token that sends no {@code page}, {@code since}, or {@code per_page}.
 *
 * <p>Further pages come only from {@code Link: rel="next"} on the response.
 */
public record GitHubNoneRequest() implements GitHubPageRequest {

    /**
     * Always empty; this token does not set {@code per_page}.
     *
     * @return empty
     */
    @Override
    public OptionalInt explicitPerPage() {
        return OptionalInt.empty();
    }

    /**
     * Writes no pagination query parameters.
     *
     * @param query mutable query map
     */
    @Override
    public void putQueryParameters(final Map<String, String> query) {
        // no query params
    }
}
