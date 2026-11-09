package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

/** Kind of {@link GitHubPageRequest} an endpoint may accept. */
public enum GitHubPageRequestKind {

    /**
     * {@link GitHubNoneRequest} — no pagination query parameters; always accepted by
     * {@link GitHubPageRequestKindValidators#requireAllowedPageRequestKind(GitHubPageRequest, java.util.Set)}.
     */
    NONE,

    /** {@link GitHubPageNumberRequest} — numbered {@code page} / {@code per_page}. */
    PAGE,

    /** {@link GitHubSinceRequest} — id-based {@code since} / {@code per_page}. */
    SINCE,

    /** {@link GitHubCursorRequest} — {@code before} / {@code after} cursors. */
    CURSOR,

    /** {@link GitHubDeliveryCursorRequest} — webhook delivery {@code cursor} / {@code per_page}. */
    DELIVERY_CURSOR,

    /** {@link GitHubStartIndexRequest} — 1-based {@code startIndex} / {@code count}, next page from the body. */
    START_INDEX;

    /**
     * Kind for {@code request}'s runtime type.
     *
     * @param request pagination token
     * @return kind
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public static GitHubPageRequestKind of(final GitHubPageRequest request) {
        requireNonNull(request, "request");
        return switch (request) {
            case final GitHubNoneRequest ignored -> NONE;
            case final GitHubPageNumberRequest ignored -> PAGE;
            case final GitHubSinceRequest ignored -> SINCE;
            case final GitHubCursorRequest ignored -> CURSOR;
            case final GitHubDeliveryCursorRequest ignored -> DELIVERY_CURSOR;
            case final GitHubStartIndexRequest ignored -> START_INDEX;
        };
    }

    /**
     * Wire label used in validation messages.
     *
     * @return label
     */
    public String label() {
        return switch (this) {
            case NONE -> "none";
            case PAGE -> "page";
            case SINCE -> "since";
            case CURSOR -> "cursor";
            case DELIVERY_CURSOR -> "delivery cursor";
            case START_INDEX -> "start index";
        };
    }
}
