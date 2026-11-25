package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireBetween;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.pagination.GitHubIssueSearchType;
import io.github.piotr_janczukowicz.github.pagination.GitHubLexicalFallbackReason;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKind;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKindValidators;
import io.github.piotr_janczukowicz.github.pagination.GitHubSearchEnvelope;
import io.github.piotr_janczukowicz.github.pagination.GitHubStartIndexRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubTaskCounts;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.util.ListUtils;
import io.github.piotr_janczukowicz.util.SetUtils;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

/**
 * How a list GET decodes items and binds {@code per_page}. Further pages come from the {@code Link} header, except for
 * a {@link GitHubStartIndexRequest} walk, which derives them from the body.
 *
 * @param allowedKinds accepted active pagination kinds
 *     ({@link io.github.piotr_janczukowicz.github.pagination.GitHubNoneRequest} always allowed)
 * @param decoder maps one HTTP response to a page body once JSON decode is bound
 * @param sendPerPage whether to send {@code per_page} on the first request
 * @param maxPerPage endpoint maximum for {@code per_page}
 * @param searchDecoder reads a Search API page with its envelope and makes the walk a search walk (the result cap, the
 *     envelope on the page), or {@code null} for a list
 * @param <T> item type
 */
public record GitHubListSpec<T>(
        Set<GitHubPageRequestKind> allowedKinds,
        Decoder<T> decoder,
        boolean sendPerPage,
        int maxPerPage,
        @Nullable SearchDecoder<T> searchDecoder) {

    public GitHubListSpec {
        allowedKinds = SetUtils.copyOf(allowedKinds, "allowedKinds");
        requireNonNull(decoder, "decoder");
        requireBetween(maxPerPage, 1, PaginationConfiguration.MAX_PER_PAGE, "maxPerPage");
    }

    /**
     * Spec of a list.
     *
     * @param allowedKinds accepted active pagination kinds
     * @param decoder maps one HTTP response to a page body once JSON decode is bound
     * @param sendPerPage whether to send {@code per_page} on the first request
     * @param maxPerPage endpoint maximum for {@code per_page}
     */
    public GitHubListSpec(
            final Set<GitHubPageRequestKind> allowedKinds,
            final Decoder<T> decoder,
            final boolean sendPerPage,
            final int maxPerPage) {
        this(allowedKinds, decoder, sendPerPage, maxPerPage, null);
    }

    /**
     * Numbered {@code page}/{@code per_page} array list.
     *
     * @param type list item type
     * @param <T> item type
     * @return spec
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static <T> GitHubListSpec<T> pages(final JsonType<List<T>> type) {
        return array(GitHubPageRequestKindValidators.PAGE, type);
    }

    /**
     * JSON array list for {@code allowedKinds}, sending {@code per_page}.
     *
     * @param allowedKinds accepted active pagination kinds
     * @param type list item type
     * @param <T> item type
     * @return spec
     * @throws NullPointerException if {@code allowedKinds} or {@code type} is {@code null}
     */
    public static <T> GitHubListSpec<T> array(
            final Set<GitHubPageRequestKind> allowedKinds, final JsonType<List<T>> type) {
        return arraySpec(allowedKinds, type, true);
    }

    /**
     * Id {@code since} + {@code per_page} array list (no {@code page}).
     *
     * @param type list item type
     * @param <T> item type
     * @return spec
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static <T> GitHubListSpec<T> since(final JsonType<List<T>> type) {
        return arraySpec(GitHubPageRequestKindValidators.SINCE, type, true);
    }

    /**
     * Wrapped object list ({@code total_count} plus one array, or a single array).
     *
     * @param allowedKinds accepted active pagination kinds
     * @param wrapperType JSON object type
     * @param items array extractor; {@code null} becomes an empty list
     * @param totalCount count extractor, or {@code null} when the object has no count; may return {@code null} when the
     *     body omits it
     * @param <T> item type
     * @param <W> wrapper type
     * @return spec
     * @throws NullPointerException if {@code allowedKinds}, {@code wrapperType}, or {@code items} is {@code null}
     */
    public static <T, W> GitHubListSpec<T> wrapper(
            final Set<GitHubPageRequestKind> allowedKinds,
            final Class<W> wrapperType,
            final Function<W, @Nullable List<T>> items,
            final @Nullable Function<W, @Nullable Integer> totalCount) {
        return wrapper(allowedKinds, wrapperType, items, totalCount, PaginationConfiguration.MAX_PER_PAGE);
    }

    /**
     * Wrapped object list ({@code total_count} plus one array, or a single array) for an endpoint whose
     * {@code per_page} maximum is below the default.
     *
     * @param allowedKinds accepted active pagination kinds
     * @param wrapperType JSON object type
     * @param items array extractor; {@code null} becomes an empty list
     * @param totalCount count extractor, or {@code null} when the object has no count; may return {@code null} when the
     *     body omits it
     * @param maxPerPage endpoint maximum for {@code per_page}
     * @param <T> item type
     * @param <W> wrapper type
     * @return spec
     * @throws NullPointerException if {@code allowedKinds}, {@code wrapperType}, or {@code items} is {@code null}
     * @throws IllegalArgumentException if {@code maxPerPage} is outside {@code 1..MAX_PER_PAGE}
     */
    // S4276: a body may omit total_count, so the extractor returns null; ToIntFunction cannot express that.
    @SuppressWarnings("java:S4276")
    public static <T, W> GitHubListSpec<T> wrapper(
            final Set<GitHubPageRequestKind> allowedKinds,
            final Class<W> wrapperType,
            final Function<W, @Nullable List<T>> items,
            final @Nullable Function<W, @Nullable Integer> totalCount,
            final int maxPerPage) {
        requireNonNull(wrapperType, "wrapperType");
        requireNonNull(items, "items");
        return GitHubListSpec.<T>builder()
                .allowedKinds(allowedKinds)
                .decoder(json -> response -> {
                    final W wrapper = json.decode(response, wrapperType);
                    final List<T> listed = items.apply(wrapper);
                    final List<T> decoded = ListUtils.orEmpty(listed);
                    if (totalCount == null) {
                        return GitHubPageBody.of(decoded);
                    }
                    final Integer total = totalCount.apply(wrapper);
                    if (total == null) {
                        return GitHubPageBody.of(decoded);
                    }
                    return GitHubPageBody.of(decoded, total);
                })
                .maxPerPage(maxPerPage)
                .build();
    }

    /**
     * SCIM-style wrapped list: {@code totalResults} plus one array, paged by {@code startIndex} and {@code count}. The
     * next page is derived from the body (see {@link GitHubStartIndexRequest#next(int, Integer)}), not from the
     * {@code Link} header, and {@code per_page} is never sent.
     *
     * @param wrapperType JSON object type
     * @param items array extractor; {@code null} becomes an empty list
     * @param totalResults total-results extractor; a body without a total ends the walk after its first page
     * @param <T> item type
     * @param <W> wrapper type
     * @return spec
     * @throws NullPointerException if {@code wrapperType}, {@code items}, or {@code totalResults} is {@code null}
     */
    public static <T, W> GitHubListSpec<T> startIndexWrapper(
            final Class<W> wrapperType,
            final Function<W, @Nullable List<T>> items,
            final Function<W, @Nullable Integer> totalResults) {
        requireNonNull(totalResults, "totalResults");
        return wrapper(GitHubPageRequestKindValidators.START_INDEX, wrapperType, items, totalResults)
                .withoutPerPage();
    }

    /**
     * Wrapped agent task list: one array plus the two task counts of the body instead of a single {@code total_count}.
     *
     * @param allowedKinds accepted active pagination kinds
     * @param wrapperType JSON object type
     * @param items array extractor; {@code null} becomes an empty list
     * @param taskCounts extractor of the task counts of the body
     * @param <T> item type
     * @param <W> wrapper type
     * @return spec
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T, W> GitHubListSpec<T> taskWrapper(
            final Set<GitHubPageRequestKind> allowedKinds,
            final Class<W> wrapperType,
            final Function<W, @Nullable List<T>> items,
            final Function<W, GitHubTaskCounts> taskCounts) {
        requireNonNull(wrapperType, "wrapperType");
        requireNonNull(items, "items");
        requireNonNull(taskCounts, "taskCounts");
        return GitHubListSpec.<T>builder()
                .allowedKinds(allowedKinds)
                .decoder(json -> response -> {
                    final W wrapper = json.decode(response, wrapperType);
                    final List<T> listed = items.apply(wrapper);
                    return GitHubPageBody.of(ListUtils.orEmpty(listed), taskCounts.apply(wrapper));
                })
                .maxPerPage(PaginationConfiguration.MAX_PER_PAGE)
                .build();
    }

    /**
     * Search API page: an object with the matches, their count and whether the result set is incomplete. The walk is a
     * search walk, which stops at the Search API result cap, and the page carries the envelope.
     *
     * @param wrapperType JSON object type
     * @param items match extractor; {@code null} becomes an empty list
     * @param totalCount total matches
     * @param incompleteResults whether GitHub considers the result set incomplete
     * @param <T> item type
     * @param <W> wrapper type
     * @return spec
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T, W> GitHubListSpec<T> search(
            final Class<W> wrapperType,
            final Function<W, @Nullable List<T>> items,
            final ToIntFunction<W> totalCount,
            final Predicate<W> incompleteResults) {
        return search(wrapperType, items, totalCount, incompleteResults, wrapper -> null, wrapper -> null);
    }

    /**
     * Search API page of the issue search: like {@link #search(Class, Function, ToIntFunction, Predicate)}, with the
     * search type and the lexical fallback reasons of the body.
     *
     * @param wrapperType JSON object type
     * @param items match extractor; {@code null} becomes an empty list
     * @param totalCount total matches
     * @param incompleteResults whether GitHub considers the result set incomplete
     * @param searchType the search type of the body, or {@code null}
     * @param lexicalFallbackReasons the lexical fallback reasons of the body, or {@code null}
     * @param <T> item type
     * @param <W> wrapper type
     * @return spec
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T, W> GitHubListSpec<T> search(
            final Class<W> wrapperType,
            final Function<W, @Nullable List<T>> items,
            final ToIntFunction<W> totalCount,
            final Predicate<W> incompleteResults,
            final Function<W, @Nullable GitHubIssueSearchType> searchType,
            final Function<W, @Nullable List<GitHubLexicalFallbackReason>> lexicalFallbackReasons) {
        requireNonNull(wrapperType, "wrapperType");
        requireNonNull(items, "items");
        requireNonNull(totalCount, "totalCount");
        requireNonNull(incompleteResults, "incompleteResults");
        requireNonNull(searchType, "searchType");
        requireNonNull(lexicalFallbackReasons, "lexicalFallbackReasons");
        final SearchDecoder<T> searchDecoder = json -> response -> {
            final W wrapper = json.decode(response, wrapperType);
            return new GitHubSearchEnvelope<>(
                    totalCount.applyAsInt(wrapper),
                    incompleteResults.test(wrapper),
                    ListUtils.orEmpty(items.apply(wrapper)),
                    searchType.apply(wrapper),
                    lexicalFallbackReasons.apply(wrapper));
        };
        return new GitHubListSpec<>(
                GitHubPageRequestKindValidators.PAGE,
                json -> response -> {
                    final GitHubSearchEnvelope<T> envelope =
                            searchDecoder.bind(json).decode(response);
                    return GitHubPageBody.of(envelope.items(), envelope.totalCount());
                },
                true,
                PaginationConfiguration.MAX_PER_PAGE,
                searchDecoder);
    }

    /**
     * Copy that never sends {@code per_page}.
     *
     * @return spec
     */
    public GitHubListSpec<T> withoutPerPage() {
        return new GitHubListSpec<>(allowedKinds, decoder, false, maxPerPage, searchDecoder);
    }

    /**
     * Copy with a lower {@code per_page} cap, for endpoints that document a maximum below the default.
     *
     * @param maxPerPage inclusive maximum
     * @return copy with the cap
     * @throws IllegalArgumentException if {@code maxPerPage} is outside {@code 1..MAX_PER_PAGE}
     */
    public GitHubListSpec<T> withMaxPerPage(final int maxPerPage) {
        return new GitHubListSpec<>(allowedKinds, decoder, sendPerPage, maxPerPage, searchDecoder);
    }

    /**
     * Builder initialized to send {@code per_page} at {@link PaginationConfiguration#MAX_PER_PAGE}.
     *
     * @param <T> item type
     * @return builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Binds JSON decode for this spec.
     *
     * @param json JSON decode
     * @return page-body decoder
     */
    GitHubPageDecoder<T> bindDecoder(final ListJson json) {
        return decoder.bind(json);
    }

    private static <T> GitHubListSpec<T> arraySpec(
            final Set<GitHubPageRequestKind> allowedKinds, final JsonType<List<T>> type, final boolean sendPerPage) {
        requireNonNull(type, "type");
        return GitHubListSpec.<T>builder()
                .allowedKinds(allowedKinds)
                .decoder(json -> response -> GitHubPageBody.of(json.decode(response, type)))
                .sendPerPage(sendPerPage)
                .build();
    }

    /** Binds JSON decode for one list HTTP response. */
    @FunctionalInterface
    public interface Decoder<T> {

        /**
         * Decoder that uses {@code json} for body mapping.
         *
         * @param json JSON decode
         * @return page-body decoder
         */
        GitHubPageDecoder<T> bind(ListJson json);
    }

    /** Binds JSON decode for a Search API page. */
    @FunctionalInterface
    public interface SearchDecoder<T> {

        /**
         * Decoder that uses {@code json} for body mapping.
         *
         * @param json JSON decode
         * @return decoder of one search page into its envelope
         */
        GitHubSearchPageDecoder<T> bind(ListJson json);
    }

    /** JSON decode for list wrappers and arrays. */
    public interface ListJson {

        /**
         * Decodes {@code response} as {@code type}.
         *
         * @param response GitHub response
         * @param type target class
         * @param <R> result type
         * @return decoded value
         */
        <R> R decode(HttpResponse response, Class<R> type);

        /**
         * Decodes {@code response} as {@code type}.
         *
         * @param response GitHub response
         * @param type target type
         * @param <R> result type
         * @return decoded value
         */
        <R> R decode(HttpResponse response, JsonType<R> type);
    }

    /**
     * Builds a {@link GitHubListSpec}. Unset {@code sendPerPage} and {@code maxPerPage} keep the defaults.
     *
     * @param <T> item type
     */
    public static final class Builder<T> {

        private @Nullable Set<GitHubPageRequestKind> allowedKinds;
        private @Nullable Decoder<T> decoder;
        private boolean isPerPageSent = true;
        private int maxPerPage = PaginationConfiguration.MAX_PER_PAGE;

        private Builder() {}

        /**
         * Sets accepted active pagination kinds.
         *
         * @param allowedKinds kinds; copied
         * @return this builder
         * @throws NullPointerException if {@code allowedKinds} is {@code null}
         */
        public Builder<T> allowedKinds(final Set<GitHubPageRequestKind> allowedKinds) {
            this.allowedKinds = SetUtils.copyOf(allowedKinds, "allowedKinds");
            return this;
        }

        /**
         * Sets the response decoder factory.
         *
         * @param decoder binds JSON decode to one response
         * @return this builder
         * @throws NullPointerException if {@code decoder} is {@code null}
         */
        public Builder<T> decoder(final Decoder<T> decoder) {
            this.decoder = requireNonNull(decoder, "decoder");
            return this;
        }

        /**
         * Sets whether to send {@code per_page}.
         *
         * @param sendPerPage whether to send {@code per_page}
         * @return this builder
         */
        public Builder<T> sendPerPage(final boolean sendPerPage) {
            this.isPerPageSent = sendPerPage;
            return this;
        }

        /**
         * Sets the endpoint maximum for {@code per_page}.
         *
         * @param maxPerPage inclusive maximum
         * @return this builder
         */
        public Builder<T> maxPerPage(final int maxPerPage) {
            this.maxPerPage = maxPerPage;
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return spec
         * @throws NullPointerException if {@code allowedKinds} or {@code decoder} was not set
         */
        public GitHubListSpec<T> build() {
            return new GitHubListSpec<>(
                    requireNonNull(allowedKinds, "allowedKinds"),
                    requireNonNull(decoder, "decoder"),
                    isPerPageSent,
                    maxPerPage);
        }
    }
}
