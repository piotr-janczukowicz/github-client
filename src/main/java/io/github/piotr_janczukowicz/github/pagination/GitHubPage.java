package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * One HTTP page of a GitHub list or Search API response.
 *
 * <p>Pages from the same {@link GitHubPageWalk} share one walk. {@link #next()} fetches the following page only from
 * the latest page of that walk; earlier pages return empty. {@link #all()}, {@link #stream()}, {@link #pages()}, and
 * {@link #forEachPage(Consumer)} require the latest page and then advance the same walk. This type is not an
 * {@link java.util.Iterator}.
 *
 * <p>A page is a cursor over one walk. Once a stream, {@link #next()}, {@link #all()} or {@link #pages()} has advanced
 * the walk past this page, calling any of them on this page again throws {@link IllegalStateException}. To read the
 * same items twice, keep them from the first read or start a new walk.
 *
 * <p>{@link #items()} is an unmodifiable view of the decoded list; this page takes ownership of that list without
 * copying.
 *
 * <p>Search pages carry a {@link GitHubSearchEnvelope}. GitHub Search REST returns at most {@link #SEARCH_RESULT_LIMIT}
 * results per query; the walk stops once the items received (counted from the starting page) reach that cap or the
 * response has no {@code Link rel=next}. {@code total_count} is not consulted.
 *
 * <p>Not thread-safe: a page and the walk it shares hold unsynchronized mutable state, so all pages of one walk, and
 * the streams made from them, must stay confined to a single thread or be guarded by the caller.
 */
public final class GitHubPage<T> {

    /** Inclusive maximum number of search results GitHub returns for one query. */
    public static final int SEARCH_RESULT_LIMIT = 1000;

    private final List<T> items;
    private final GitHubPageWalk<T> session;
    private final int pageCount;
    private final Details<T> details;

    /**
     * One decoded page bound to {@code session}, with no wrapper {@code total_count} and no search envelope.
     *
     * @param items items for this HTTP page
     * @param session walk that produced this page
     * @param pageCount 1-based index of this page in the walk
     */
    public GitHubPage(final List<T> items, final GitHubPageWalk<T> session, final int pageCount) {
        this(items, session, pageCount, new ListDetails<>(null, null));
    }

    /**
     * One decoded list page bound to {@code session}.
     *
     * @param items items for this HTTP page
     * @param session walk that produced this page
     * @param pageCount 1-based index of this page in the walk
     * @param totalCount wrapper {@code total_count} for this page, or {@code null} for a bare array
     */
    public GitHubPage(
            final List<T> items,
            final GitHubPageWalk<T> session,
            final int pageCount,
            final @Nullable Integer totalCount) {
        this(items, session, pageCount, new ListDetails<>(totalCount, null));
    }

    /**
     * One decoded list page bound to {@code session}, whose body also carries the agent task counts.
     *
     * @param items items for this HTTP page
     * @param session walk that produced this page
     * @param pageCount 1-based index of this page in the walk
     * @param totalCount wrapper {@code total_count} for this page, or {@code null} when the body has none
     * @param taskCounts the counts of the task list body
     */
    public GitHubPage(
            final List<T> items,
            final GitHubPageWalk<T> session,
            final int pageCount,
            final @Nullable Integer totalCount,
            final GitHubTaskCounts taskCounts) {
        this(items, session, pageCount, new ListDetails<>(totalCount, requireNonNull(taskCounts, "taskCounts")));
    }

    /**
     * One decoded search page bound to {@code session}.
     *
     * @param envelope envelope metadata and items from this HTTP page
     * @param session walk that produced this page
     * @param pageCount 1-based index of this page in the walk
     */
    public GitHubPage(final GitHubSearchEnvelope<T> envelope, final GitHubPageWalk<T> session, final int pageCount) {
        this(requireNonNull(envelope, "envelope").items(), session, pageCount, new SearchDetails<>(envelope));
    }

    private GitHubPage(
            final List<T> items, final GitHubPageWalk<T> session, final int pageCount, final Details<T> details) {
        this.items = List.copyOf(requireNonNull(items, "items"));
        this.session = requireNonNull(session, "session");
        this.pageCount = requireAtLeast(pageCount, 1, "pageCount");
        this.details = details;
    }

    /**
     * Items decoded from this HTTP page. Empty when GitHub answered 204 No Content to a list request.
     *
     * @return unmodifiable list of items for this page
     */
    public List<T> items() {
        return items;
    }

    /**
     * Wrapper {@code total_count} from this page's body when the list is an object envelope, or Search API
     * {@code total_count} when this is a search page.
     *
     * @return total count from this page, or {@code null} for a bare array
     */
    public @Nullable Integer totalCount() {
        return details.totalCount();
    }

    /**
     * {@code total_active_count} from this page's body when it is an agent task list.
     *
     * @return total count of active (non-archived) tasks, or {@code null} for any other page or when the body omits it
     */
    public @Nullable Integer totalActiveCount() {
        return details.totalActiveCount();
    }

    /**
     * {@code total_archived_count} from this page's body when it is an agent task list.
     *
     * @return total count of archived tasks, or {@code null} for any other page or when the body omits it
     */
    public @Nullable Integer totalArchivedCount() {
        return details.totalArchivedCount();
    }

    /**
     * Search API envelope when this page was decoded from a search response.
     *
     * @return envelope, or {@code null} for a list page
     */
    public @Nullable GitHubSearchEnvelope<T> envelope() {
        return details.envelope();
    }

    /**
     * Whether GitHub considers the search result set incomplete.
     *
     * @return incomplete-results flag, or {@code false} when this is not a search page
     */
    public boolean areResultsIncomplete() {
        return details.areResultsIncomplete();
    }

    /**
     * Issues-search {@code search_type} field when present on the envelope.
     *
     * @return search type, or {@code null} when absent or this is not a search page
     */
    public @Nullable GitHubIssueSearchType searchType() {
        return details.searchType();
    }

    /**
     * Issues-search lexical fallback reasons when present on the envelope.
     *
     * @return unmodifiable reasons, or empty when absent or this is not a search page
     */
    public List<GitHubLexicalFallbackReason> lexicalFallbackReasons() {
        return details.lexicalFallbackReasons();
    }

    /**
     * Fetches the next HTTP page when the response has a {@code rel="next"} link, or, for a walk started with a
     * {@link GitHubStartIndexRequest}, when the body shows results beyond this page. Must be called on the latest page
     * of the walk: a page that has already been followed is stale, and asking it for its next page throws instead of
     * returning empty, which would look like the end of the data.
     *
     * @return next page, or empty when there is no next link (for a start index walk: when the body shows no results
     *     beyond this page)
     * @throws IllegalStateException if this page is not the latest in the walk
     * @throws IOException if the transport fails while fetching a later page
     * @throws GitHubPaginationLimitExceededException if a configured {@code maxPages} was reached with more pages
     *     available
     */
    public Optional<GitHubPage<T>> next() throws IOException {
        session.requireLatest(this);
        return session.loadNext(this);
    }

    /**
     * All items from this page and every followed page until no further page remains. Must be called on the latest page
     * of the walk; afterward {@link #next()} on this page throws, because the page is then stale.
     *
     * <p><strong>Unbounded by default:</strong> unless {@link PaginationConfiguration#maxPages()} is set, this follows
     * every page and holds all items in memory, and its cost is one HTTP request per page counted against the rate
     * limit. Large lists, such as {@code /issues}, search results, or the repositories of a big organization, can be
     * thousands of items; in a local test 100,000 items took about 1 GB, and a heap that is too small fails with an
     * {@link OutOfMemoryError} rather than a library exception. Set {@code maxPages} on the client's
     * {@link PaginationConfiguration}, or use {@link #stream()} with {@code limit}, or step through pages with
     * {@link #next()}, to bound the work.
     *
     * <p><strong>All or nothing:</strong> if a later page fails, the exception is thrown and the items of the pages
     * already read are discarded. To keep them, read page by page with {@link #forEachPage(Consumer)} or
     * {@link #next()}.
     *
     * @return unmodifiable concatenated items from walked pages
     * @throws IllegalStateException if this page is not the latest in the walk
     * @throws IOException if the transport fails while fetching a later page
     * @throws GitHubPaginationLimitExceededException if a configured {@code maxPages} was reached with more pages
     *     available
     */
    public List<T> all() throws IOException {
        session.requireLatest(this);
        return GitHubPageStreams.all(this);
    }

    /**
     * Items of this page and later pages, fetched only as the stream is consumed. Must be called on the latest page of
     * the walk. Transport failures are {@link java.io.UncheckedIOException}.
     *
     * <p>Pages are fetched lazily, so {@code limit} or a short-circuiting terminal operation stops further requests.
     * Without one the stream walks every page; {@link PaginationConfiguration#maxPages()} is unbounded by default, so
     * set it for large lists such as {@code /issues} or search results.
     *
     * <p>Consuming the stream advances the walk, so a second {@code stream()} on this same page object throws once the
     * first has moved past it; take the items from the first stream, or start a new walk.
     *
     * @return sequential stream of items
     * @throws IllegalStateException if this page is not the latest in the walk
     */
    public Stream<T> stream() {
        session.requireLatest(this);
        return GitHubPageStreams.items(this);
    }

    /**
     * This page and later pages, fetched only as the stream is consumed. Must be called on the latest page of the walk.
     * Transport failures are {@link java.io.UncheckedIOException}.
     *
     * @return sequential stream of pages
     * @throws IllegalStateException if this page is not the latest in the walk
     */
    public Stream<GitHubPage<T>> pages() {
        session.requireLatest(this);
        return GitHubPageStreams.pages(this);
    }

    /**
     * Invokes {@code consumer} for this page and each followed page until no further page remains. Must be called on
     * the latest page of the walk; afterward {@link #next()} on this page throws, because the page is then stale.
     *
     * <p>{@code consumer} must not invoke {@link #next()}, {@link #all()}, {@link #stream()}, {@link #pages()}, or this
     * method again; the walk is shared.
     *
     * @param consumer callback for each page
     * @throws IllegalStateException if this page is not the latest in the walk
     * @throws IOException if the transport fails while fetching a later page
     * @throws GitHubPaginationLimitExceededException if a configured {@code maxPages} was reached with more pages
     *     available
     */
    public void forEachPage(final Consumer<GitHubPage<T>> consumer) throws IOException {
        requireNonNull(consumer, "consumer");
        session.requireLatest(this);
        GitHubPageStreams.forEachPage(this, consumer);
    }

    /**
     * 1-based index of this page in its walk.
     *
     * @return page count
     */
    public int pageCount() {
        return pageCount;
    }

    /** What a page knows beyond its items: the wrapper count of a list page, or the envelope of a search page. */
    private sealed interface Details<T> permits ListDetails, SearchDetails {

        @Nullable Integer totalCount();

        @Nullable Integer totalActiveCount();

        @Nullable Integer totalArchivedCount();

        @Nullable GitHubSearchEnvelope<T> envelope();

        boolean areResultsIncomplete();

        @Nullable GitHubIssueSearchType searchType();

        List<GitHubLexicalFallbackReason> lexicalFallbackReasons();
    }

    private record ListDetails<T>(
            @Nullable Integer totalCount, @Nullable GitHubTaskCounts taskCounts) implements Details<T> {

        @Override
        public @Nullable Integer totalActiveCount() {
            return taskCounts == null ? null : taskCounts.totalActiveCount();
        }

        @Override
        public @Nullable Integer totalArchivedCount() {
            return taskCounts == null ? null : taskCounts.totalArchivedCount();
        }

        @Override
        public @Nullable GitHubSearchEnvelope<T> envelope() {
            return null;
        }

        @Override
        public boolean areResultsIncomplete() {
            return false;
        }

        @Override
        public @Nullable GitHubIssueSearchType searchType() {
            return null;
        }

        @Override
        public List<GitHubLexicalFallbackReason> lexicalFallbackReasons() {
            return List.of();
        }
    }

    private record SearchDetails<T>(GitHubSearchEnvelope<T> envelope) implements Details<T> {

        @Override
        public Integer totalCount() {
            return envelope.totalCount();
        }

        @Override
        public @Nullable Integer totalActiveCount() {
            return null;
        }

        @Override
        public @Nullable Integer totalArchivedCount() {
            return null;
        }

        @Override
        public boolean areResultsIncomplete() {
            return envelope.incompleteResults();
        }

        @Override
        public @Nullable GitHubIssueSearchType searchType() {
            return envelope.searchType();
        }

        @Override
        public List<GitHubLexicalFallbackReason> lexicalFallbackReasons() {
            final List<GitHubLexicalFallbackReason> reasons = envelope.lexicalFallbackReasons();
            return reasons == null ? List.of() : reasons;
        }
    }
}
