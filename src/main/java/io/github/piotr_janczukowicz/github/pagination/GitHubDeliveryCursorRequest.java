package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Webhook delivery cursor pagination token.
 *
 * @param cursor delivery cursor for the next page, or {@code null}; read it as {@link #cursorValue()}
 * @param perPage explicit per-page size, or {@code null} to take it from the client configuration; read it as
 *     {@link #explicitPerPage()}
 */
public record GitHubDeliveryCursorRequest(
        @Nullable String cursor, @Nullable Integer perPage) implements GitHubPageRequest {

    /** Query parameter name for the delivery cursor. */
    public static final String CURSOR_PARAM = "cursor";

    public GitHubDeliveryCursorRequest {
        if (cursor != null) {
            requireNotBlank(cursor, CURSOR_PARAM);
        }
        if (perPage != null) {
            requireAtLeast(perPage, 1, "perPage");
        }
    }

    /**
     * Delivery cursor pagination without {@code cursor}; per-page size comes from client configuration at bind.
     *
     * @return delivery cursor request
     */
    public static GitHubDeliveryCursorRequest unpositioned() {
        return new GitHubDeliveryCursorRequest(null, null);
    }

    /**
     * Delivery cursor pagination without {@code cursor}.
     *
     * @param perPage items per page
     * @return delivery cursor request
     * @throws IllegalArgumentException if {@code perPage} is invalid
     */
    public static GitHubDeliveryCursorRequest of(final int perPage) {
        return new GitHubDeliveryCursorRequest(null, perPage);
    }

    /**
     * Delivery cursor pagination with {@code cursor}; per-page size comes from client configuration at bind.
     *
     * @param cursor delivery cursor for later items
     * @return delivery cursor request
     * @throws NullPointerException if {@code cursor} is {@code null}
     * @throws IllegalArgumentException if {@code cursor} is blank
     */
    public static GitHubDeliveryCursorRequest of(final String cursor) {
        return new GitHubDeliveryCursorRequest(requireNotBlank(cursor, CURSOR_PARAM), null);
    }

    /**
     * Delivery cursor pagination with {@code cursor}.
     *
     * @param cursor delivery cursor for later items
     * @param perPage items per page
     * @return delivery cursor request
     * @throws NullPointerException if {@code cursor} is {@code null}
     * @throws IllegalArgumentException if {@code cursor} or {@code perPage} is invalid
     */
    public static GitHubDeliveryCursorRequest of(final String cursor, final int perPage) {
        return new GitHubDeliveryCursorRequest(requireNotBlank(cursor, CURSOR_PARAM), perPage);
    }

    /**
     * Delivery cursor for the next page.
     *
     * @return {@code cursor}, or empty
     */
    public Optional<String> cursorValue() {
        return Optional.ofNullable(cursor);
    }

    @Override
    public OptionalInt explicitPerPage() {
        return perPage == null ? OptionalInt.empty() : OptionalInt.of(perPage);
    }

    /**
     * Writes {@code cursor} when present.
     *
     * @param query mutable query map
     */
    @Override
    public void putQueryParameters(final Map<String, String> query) {
        if (cursor != null) {
            query.put(CURSOR_PARAM, cursor);
        }
    }
}
