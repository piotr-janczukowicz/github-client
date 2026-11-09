package io.github.piotr_janczukowicz.github.pagination;

import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Sealed pagination token for GitHub list endpoints.
 *
 * <p>Factories and record constructors validate {@code page} and explicit {@code perPage} at creation. The effective
 * {@code per_page} is resolved when the request is bound: explicit {@code perPage} must not exceed the endpoint maximum
 * (100 unless the client bind overrides it). {@link PaginationConfiguration#perPage()} is the default sent when the
 * request omits {@code perPage}.
 */
public sealed interface GitHubPageRequest
        permits GitHubPageNumberRequest,
                GitHubSinceRequest,
                GitHubNoneRequest,
                GitHubCursorRequest,
                GitHubDeliveryCursorRequest,
                GitHubStartIndexRequest {

    /**
     * Explicit {@code per_page} from the factory, if any.
     *
     * @return per-page size when set at creation; empty to use client configuration at bind
     */
    OptionalInt explicitPerPage();

    /**
     * Writes this token's query parameters ({@code page}, {@code since}, or cursor params) into {@code query}. Does not
     * write {@code per_page}.
     *
     * @param query mutable query map
     */
    void putQueryParameters(Map<String, String> query);

    /**
     * First page; per-page size comes from client configuration at bind.
     *
     * @return page request for page 1
     */
    static GitHubPageRequest byPage() {
        return GitHubPageNumberRequest.of(1);
    }

    /**
     * First since page; per-page size comes from client configuration at bind.
     *
     * @return since request without {@code since}
     */
    static GitHubPageRequest bySince() {
        return GitHubSinceRequest.first();
    }

    /**
     * Token that omits {@code page}, {@code since}, and {@code per_page}. GitHub's defaults apply; further pages come
     * only from {@code Link: rel="next"}.
     *
     * @return request that sends no pagination query parameters
     */
    static GitHubPageRequest none() {
        return new GitHubNoneRequest();
    }

    /**
     * Cursor-based pagination without {@code before} or {@code after}; per-page size comes from client configuration at
     * bind.
     *
     * @return cursor request
     */
    static GitHubPageRequest byCursor() {
        return GitHubCursorRequest.unpositioned();
    }

    /**
     * Webhook delivery cursor pagination without {@code cursor}; per-page size comes from client configuration at bind.
     *
     * @return delivery cursor request
     */
    static GitHubPageRequest byDeliveryCursor() {
        return GitHubDeliveryCursorRequest.unpositioned();
    }

    /**
     * First SCIM-style index page ({@code startIndex=1}) with no {@code count}, so the server chooses the page size.
     * The walk follows the body's {@code totalResults}: a server that ignores {@code startIndex} and keeps returning
     * full pages is followed until {@code totalResults} is covered or {@code maxPages} is reached. A {@link #none()}
     * token on a start-index endpoint is sent as this first page.
     *
     * @return start index request
     */
    static GitHubPageRequest byStartIndex() {
        return GitHubStartIndexRequest.first();
    }
}
