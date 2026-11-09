package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;

import java.util.Map;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Since-id pagination token.
 *
 * @param since since id to start after, or {@code null} for the first page
 * @param perPage explicit per-page size, or {@code null} to take it from the client configuration; read it as
 *     {@link #explicitPerPage()}
 */
public record GitHubSinceRequest(
        @Nullable Long since, @Nullable Integer perPage) implements GitHubPageRequest {

    /** Query parameter name for since id. */
    public static final String SINCE_PARAM = "since";

    public GitHubSinceRequest {
        if (perPage != null) {
            requireAtLeast(perPage, 1, "perPage");
        }
    }

    /**
     * First since page; per-page size comes from client configuration at bind.
     *
     * @return since request without {@code since}
     */
    public static GitHubSinceRequest first() {
        return new GitHubSinceRequest(null, null);
    }

    /**
     * First since page with explicit per-page size (no {@code since} query parameter).
     *
     * @param perPage items per page
     * @return since request
     * @throws IllegalArgumentException if {@code perPage} is invalid
     */
    public static GitHubSinceRequest first(final int perPage) {
        return new GitHubSinceRequest(null, perPage);
    }

    /**
     * Since-based pagination with since id; per-page size comes from client configuration at bind.
     *
     * @param since user id to start after
     * @return since request
     */
    public static GitHubSinceRequest of(final long since) {
        return new GitHubSinceRequest(since, null);
    }

    /**
     * Since-based pagination with explicit since id and per-page size.
     *
     * @param since user id to start after
     * @param perPage items per page
     * @return since request
     * @throws IllegalArgumentException if {@code perPage} is invalid
     */
    public static GitHubSinceRequest of(final long since, final int perPage) {
        return new GitHubSinceRequest(since, perPage);
    }

    @Override
    public OptionalInt explicitPerPage() {
        return perPage == null ? OptionalInt.empty() : OptionalInt.of(perPage);
    }

    /**
     * Writes {@code since} when present.
     *
     * @param query mutable query map
     */
    @Override
    public void putQueryParameters(final Map<String, String> query) {
        if (since != null) {
            query.put(SINCE_PARAM, String.valueOf(since));
        }
    }
}
