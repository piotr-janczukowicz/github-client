package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageNumberRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKind;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKindValidators;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageWalk;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationException;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationLimitExceededException;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationLoopException;
import io.github.piotr_janczukowicz.github.pagination.GitHubSearchEnvelope;
import io.github.piotr_janczukowicz.github.pagination.GitHubStartIndexRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubTaskCounts;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpClientExecutor;
import io.github.piotr_janczukowicz.http.client.HttpOriginViolationException;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a list or search page walk over an {@link HttpClientExecutor}.
 *
 * <p>{@link GitHubListSpec} describes the list endpoint. Search uses {@link #startSearch}. Both construct the same
 * session: the {@code Link} header's {@code rel="next"} is the only source of the next page, as GitHub documents. A
 * response without a {@code Link} header, or with one that has no {@code rel="next"}, ends the walk. The one exception
 * is a walk started with a {@link GitHubStartIndexRequest}: its next index is derived from the body (items received and
 * the wrapper total), and no {@code Link} header is read. Allowed pagination kinds are enforced here (and in
 * {@link io.github.piotr_janczukowicz.github.client.GitHubCaller#pageParameters} for one-shot binds).
 *
 * <p>A list endpoint that answers HTTP 204 No Content (GitHub does this instead of an empty array when there is no
 * data) yields an empty page with a {@code null} total count; the body is not decoded and its {@code Link} header, if
 * any, is still read. A 200 with an empty body is not a 204 and still fails. Search walks do not special-case 204.
 *
 * <p>Search walks stop at the hard 1k cap: the items actually received, plus the starting page offset when
 * {@code per_page} is sent. {@code total_count} is not consulted, so a drifting count never hides a {@code next} link.
 *
 * <p>When {@link PaginationConfiguration#maxPages()} pages were read and another page is still available, loading the
 * next one fails with {@link GitHubPaginationLimitExceededException}; it never silently stops early. A relative
 * {@code Link} target is resolved against the URI of the request that returned the header (RFC 8288) and still passes
 * the client's origin guard. The walk is confined to one thread; see {@link GitHubPage}.
 */
public final class GitHubPagers {

    private static final GitHubListSpec.ListJson DEFAULT_JSON = new GitHubCodecListJson();

    private static final String HOST_HINT = "; the Link host differs from the base URI host: configure the client with"
            + " the instance's configured hostname";

    private static final Logger LOG = LoggerFactory.getLogger(GitHubPagers.class);

    private static final String STALE_MESSAGE =
            "GitHubPage.next()/all()/stream()/pages()/forEachPage() may only be called on the latest page of this walk";

    private GitHubPagers() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Fetches the first HTTP page of a list endpoint described by {@code spec}.
     *
     * @param executor HTTP executor, normally the GitHub client
     * @param pagination page size and page-follow limits
     * @param target path, endpoint-specific filters, and headers merged last
     * @param request pagination token
     * @param spec list decode, kinds, and {@code per_page} bind
     * @param <T> item type
     * @return first decoded page
     * @throws IOException if the transport fails
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the kind of {@code request} is not in {@code spec}'s allowed kinds, or an
     *     explicit {@code per_page} exceeds the endpoint maximum
     */
    public static <T> GitHubPage<T> start(
            final HttpClientExecutor executor,
            final PaginationConfiguration pagination,
            final GitHubPageTarget target,
            final GitHubPageRequest request,
            final GitHubListSpec<T> spec)
            throws IOException {
        requireNonNull(executor, "executor");
        requireNonNull(pagination, "pagination");
        requireNonNull(target, "target");
        requireNonNull(request, "request");
        requireNonNull(spec, "spec");
        GitHubPageRequestKindValidators.requireAllowedPageRequestKind(request, spec.allowedKinds());
        final GitHubPageDecoder<T> decoder = spec.bindDecoder(DEFAULT_JSON);
        final Paging paging = new Paging(spec.sendPerPage(), spec.maxPerPage());
        final Shape<T> shape = new Shape<>(
                response -> {
                    if (response.statusCode() == HttpStatus.NO_CONTENT.code()) {
                        return new ListPage<>(List.of(), null, null);
                    }
                    final GitHubPageBody<T> body = requireNonNull(decoder.decode(response), "items");
                    return new ListPage<>(body.items(), body.totalCount(), body.taskCounts());
                },
                false);
        final GitHubPageRequest effective =
                isStartIndexOnly(spec) && GitHubPageRequestKind.of(request) == GitHubPageRequestKind.NONE
                        ? GitHubStartIndexRequest.first()
                        : request;
        return new Session<>(executor, pagination, target, effective, paging, shape).fetchFirst();
    }

    /**
     * Fetches the first HTTP page of a search endpoint. Following pages and the result cap follow the class
     * description.
     *
     * @param executor HTTP executor, normally the GitHub client
     * @param pagination page size and page-follow limits
     * @param target search path, endpoint-specific filters, and headers merged last
     * @param request pagination token
     * @param decoder maps one search HTTP response to an envelope
     * @param <T> item type
     * @return first decoded page
     * @throws IOException if the transport fails
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code request} is not a page-number or no-pagination token
     */
    public static <T> GitHubPage<T> startSearch(
            final HttpClientExecutor executor,
            final PaginationConfiguration pagination,
            final GitHubPageTarget target,
            final GitHubPageRequest request,
            final GitHubSearchPageDecoder<T> decoder)
            throws IOException {
        requireNonNull(executor, "executor");
        requireNonNull(pagination, "pagination");
        requireNonNull(target, "target");
        requireNonNull(request, "request");
        requireNonNull(decoder, "decoder");
        GitHubPageRequestKindValidators.requireAllowedPageRequestKind(request, GitHubPageRequestKindValidators.PAGE);
        final Paging paging = new Paging(true, PaginationConfiguration.MAX_PER_PAGE);
        final Shape<T> shape =
                new Shape<>(response -> new SearchPage<>(requireNonNull(decoder.decode(response), "envelope")), true);
        return new Session<>(executor, pagination, target, request, paging, shape).fetchFirst();
    }

    static boolean isSearchCapReached(final int initialPage, final int perPage, final int resultsSeen) {
        return (long) (initialPage - 1) * perPage + resultsSeen >= GitHubPage.SEARCH_RESULT_LIMIT;
    }

    private static boolean isStartIndexOnly(final GitHubListSpec<?> spec) {
        return spec.allowedKinds().equals(GitHubPageRequestKindValidators.START_INDEX);
    }

    /** How {@code per_page} is sent for one walk. */
    private record Paging(boolean isPerPageSent, int maxPerPage) {}

    /** The decoded body of one HTTP page, which knows how to become the {@link GitHubPage} it belongs to. */
    private sealed interface DecodedPage<T> permits ListPage, SearchPage {

        List<T> items();

        GitHubPage<T> toPage(GitHubPageWalk<T> walk, int pageCount);
    }

    private record ListPage<T>(
            List<T> items,
            @Nullable Integer totalCount,
            @Nullable GitHubTaskCounts taskCounts) implements DecodedPage<T> {

        @Override
        public GitHubPage<T> toPage(final GitHubPageWalk<T> walk, final int pageCount) {
            if (taskCounts != null) {
                return new GitHubPage<>(items, walk, pageCount, totalCount, taskCounts);
            }
            return new GitHubPage<>(items, walk, pageCount, totalCount);
        }
    }

    private record SearchPage<T>(GitHubSearchEnvelope<T> envelope) implements DecodedPage<T> {

        @Override
        public List<T> items() {
            return envelope.items();
        }

        @Override
        public GitHubPage<T> toPage(final GitHubPageWalk<T> walk, final int pageCount) {
            return new GitHubPage<>(envelope, walk, pageCount);
        }
    }

    private record Shape<T>(Function<HttpResponse, DecodedPage<T>> decode, boolean hasSearchCap) {

        private Shape {
            requireNonNull(decode, "decode");
        }
    }

    private record Walk<T>(Session<T> session) implements GitHubPageWalk<T> {

        @Override
        public Optional<GitHubPage<T>> loadNext(final GitHubPage<T> current) throws IOException {
            return session.advanceFrom(current);
        }

        @Override
        public void requireLatest(final GitHubPage<T> page) {
            session.requireLatest(page.pageCount());
        }
    }

    /** Mutable walk state. Not thread-safe: confined to the one thread that walks the pages. */
    private static final class Session<T> {

        private final HttpClientExecutor http;
        private final HttpHeaders overlayHeaders;
        private final String path;
        private final Map<String, List<String>> baseQuery;
        private final boolean isPerPageSent;
        private final int perPage;
        private final int initialPage;
        private final @Nullable Integer maxPages;
        private final Shape<T> shape;
        private final Set<String> followedLinks = new HashSet<>();
        private final GitHubPageRequest firstRequest;
        private URI currentUri;
        private int latestPageCount;
        private int resultsSeen;
        private @Nullable String linkHeader;
        private @Nullable GitHubStartIndexRequest latestStartIndex;

        private Session(
                final HttpClientExecutor executor,
                final PaginationConfiguration pagination,
                final GitHubPageTarget target,
                final GitHubPageRequest request,
                final Paging paging,
                final Shape<T> shape) {
            this.http = executor;
            this.overlayHeaders = target.overlayHeaders();
            this.path = target.path();
            this.baseQuery = Collections.unmodifiableMap(HttpUris.copyQueryValues(target.query()));
            this.isPerPageSent = paging.isPerPageSent();
            if (!isPerPageSent) {
                GitHubPageRequestKindValidators.requireNoExplicitPerPage(request, noPerPageAllowed(request));
            }
            this.perPage = GitHubPageQueries.boundPerPage(request, pagination, paging.maxPerPage());
            this.initialPage = pageNumber(request);
            this.maxPages = pagination.maxPages();
            this.firstRequest = request;
            this.latestStartIndex = startIndexOf(request);
            this.currentUri = HttpUris.resolveApiPath(http.baseUri(), path, Map.of());
            // The first request counts as visited, so a Link rel="next" that points back at it is caught at once
            // instead of after one extra page.
            followedLinks.add(visitedKey(HttpUris.resolveApiPath(
                    http.baseUri(),
                    path,
                    GitHubPageQueries.queryValues(request, this.baseQuery, perPage, isPerPageSent))));
            this.shape = shape;
        }

        // The same page with its query parameters in another order is the same page, so a server that alternates the
        // order cannot walk the caller in circles.
        private static String visitedKey(final URI uri) {
            final String rawQuery = uri.getRawQuery();
            final StringBuilder key = new StringBuilder(uri.getScheme())
                    .append("://")
                    .append(uri.getRawAuthority())
                    .append(uri.getRawPath());
            if (rawQuery != null) {
                final String[] parameters = rawQuery.split("&");
                Arrays.sort(parameters);
                key.append('?').append(String.join("&", parameters));
            }
            return key.toString();
        }

        private static @Nullable GitHubStartIndexRequest startIndexOf(final GitHubPageRequest request) {
            return request instanceof final GitHubStartIndexRequest start ? start : null;
        }

        private static int pageNumber(final GitHubPageRequest request) {
            return request instanceof final GitHubPageNumberRequest pageRequest ? pageRequest.page() : 1;
        }

        private static Set<GitHubPageRequestKind> noPerPageAllowed(final GitHubPageRequest request) {
            return switch (GitHubPageRequestKind.of(request)) {
                case SINCE, NONE -> GitHubPageRequestKindValidators.SINCE;
                case CURSOR -> GitHubPageRequestKindValidators.CURSOR;
                case PAGE -> GitHubPageRequestKindValidators.PAGE;
                case DELIVERY_CURSOR -> GitHubPageRequestKindValidators.DELIVERY_CURSOR;
                case START_INDEX -> GitHubPageRequestKindValidators.START_INDEX;
            };
        }

        private GitHubPage<T> fetchFirst() throws IOException {
            return toPage(
                    http.execute(HttpCall.get(path)
                            .withQueryValues(
                                    GitHubPageQueries.queryValues(firstRequest, baseQuery, perPage, isPerPageSent))
                            .withHeaders(overlayHeaders)),
                    1);
        }

        private Optional<GitHubPage<T>> advanceFrom(final GitHubPage<T> current) throws IOException {
            final GitHubStartIndexRequest start = latestStartIndex;
            if (start != null) {
                return advanceByStartIndex(start, current);
            }
            final Optional<URI> advance = nextLink();
            if (advance.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(loadPage(advance.get(), current.pageCount()));
        }

        private Optional<GitHubPage<T>> advanceByStartIndex(
                final GitHubStartIndexRequest start, final GitHubPage<T> current) throws IOException {
            final Optional<GitHubStartIndexRequest> following =
                    start.next(current.items().size(), current.totalCount());
            if (following.isEmpty()) {
                logEnd("the body shows no results beyond this page");
                return Optional.empty();
            }
            final GitHubPage<T> page = loadPage(startIndexLink(following.get()), current.pageCount());
            latestStartIndex = following.get();
            return Optional.of(page);
        }

        private GitHubPage<T> loadPage(final URI link, final int pageCount) throws IOException {
            if (maxPages != null && pageCount >= maxPages) {
                throw new GitHubPaginationLimitExceededException(maxPages, pageCount);
            }
            if (followedLinks.contains(visitedKey(link))) {
                throw new GitHubPaginationLoopException(
                        "Link rel=\"next\" repeated after " + pageCount + " page(s): " + HttpUris.describeSafe(link));
            }
            LOG.atDebug()
                    .setMessage("Loading page {} of {}: {}")
                    .addArgument(pageCount + 1)
                    .addArgument(path)
                    .addArgument(() -> HttpUris.describeSafe(link))
                    .log();
            final GitHubPage<T> page = toPage(execute(link), pageCount + 1);
            followedLinks.add(visitedKey(link));
            currentUri = link;
            return page;
        }

        private URI startIndexLink(final GitHubStartIndexRequest following) {
            final Map<String, List<String>> query =
                    GitHubPageQueries.queryValues(following, baseQuery, perPage, isPerPageSent);
            return HttpUris.resolveApiPath(http.baseUri(), path, query);
        }

        private HttpResponse execute(final URI link) throws IOException {
            try {
                return http.execute(HttpCall.get(link).withHeaders(overlayHeaders));
            } catch (final HttpOriginViolationException ex) {
                // The origin guard refused the server-supplied Link target.
                throw new GitHubPaginationException(outsideBaseMessage(link), ex);
            }
        }

        private String outsideBaseMessage(final URI link) {
            final String message = "Link rel=\"next\" is outside the API base URI: " + HttpUris.describeSafe(link);
            return differsOnlyByOrigin(link) ? message + HOST_HINT : message;
        }

        private boolean differsOnlyByOrigin(final URI link) {
            final URI base = http.baseUri();
            return !originOf(base).equals(originOf(link))
                    && link.getPath() != null
                    && link.getPath().startsWith(base.getPath());
        }

        private static String originOf(final URI uri) {
            final String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
            final int defaultPort = "https".equals(scheme) ? 443 : 80;
            final int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
            return scheme + "://" + String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT) + ":" + port;
        }

        private Optional<URI> nextLink() {
            if (shape.hasSearchCap() && isSearchCapReached(initialPage, perPage, resultsSeen)) {
                logEnd("the search result cap is reached");
                return Optional.empty();
            }
            if (linkHeader == null) {
                logEnd("there is no Link header");
                return Optional.empty();
            }
            final Optional<URI> next = GitHubLinkHeader.parse(linkHeader).next().map(this::absoluteLink);
            if (next.isEmpty()) {
                logEnd("the Link header has no rel=\"next\"");
            }
            return next;
        }

        private void logEnd(final String reason) {
            LOG.atDebug()
                    .setMessage("No page after page {} of {}: {}")
                    .addArgument(latestPageCount)
                    .addArgument(path)
                    .addArgument(reason)
                    .log();
        }

        private void requireLatest(final int pageCount) {
            if (pageCount != latestPageCount) {
                throw new IllegalStateException(STALE_MESSAGE);
            }
        }

        private GitHubPage<T> toPage(final HttpResponse response, final int pageCount) {
            final DecodedPage<T> decoded = shape.decode().apply(response);
            final List<T> items = requireNonNull(decoded.items(), "items");
            linkHeader = response.header(HttpHeaderNames.LINK).orElse(null);
            latestPageCount = pageCount;
            resultsSeen += items.size();
            return decoded.toPage(new Walk<>(this), pageCount);
        }

        private URI absoluteLink(final URI link) {
            if (link.isAbsolute()) {
                return link;
            }
            try {
                return HttpUris.resolveReference(currentUri, link.toString());
            } catch (final IllegalArgumentException ex) {
                throw new GitHubPaginationException(
                        "Link rel=\"next\" cannot be resolved against the request URI: " + HttpUris.describeSafe(link),
                        ex);
            }
        }
    }
}
