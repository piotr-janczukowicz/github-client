package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.pagination.GitHubNoneRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Binds a pagination token to {@code per_page} and a multi-valued query map.
 *
 * <p>{@link GitHubNoneRequest} never sends {@code per_page}; {@code sendPerPage} applies to every other token. A
 * {@link io.github.piotr_janczukowicz.github.pagination.GitHubStartIndexRequest} writes its own {@code startIndex} and
 * {@code count}, so its lists are bound without {@code per_page}.
 */
public final class GitHubPageQueries {

    private GitHubPageQueries() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Effective {@code per_page}: explicit value if present, otherwise {@code min(config.perPage(), 100)}. An explicit
     * value must not exceed 100.
     *
     * @param request pagination token
     * @param config client pagination settings
     * @return per-page size to send
     * @throws NullPointerException if {@code request} or {@code config} is {@code null}
     * @throws IllegalArgumentException if explicit {@code perPage} exceeds 100
     */
    public static int boundPerPage(final GitHubPageRequest request, final PaginationConfiguration config) {
        return boundPerPage(request, config, PaginationConfiguration.MAX_PER_PAGE);
    }

    /**
     * Effective {@code per_page}: explicit value if present, otherwise {@code min(config.perPage(), maxPerPage)}. An
     * explicit value must not exceed {@code maxPerPage}.
     *
     * @param request pagination token
     * @param config client pagination settings
     * @param maxPerPage endpoint maximum for {@code per_page}
     * @return per-page size to send
     * @throws NullPointerException if {@code request} or {@code config} is {@code null}
     * @throws IllegalArgumentException if explicit {@code perPage} exceeds {@code maxPerPage}
     */
    public static int boundPerPage(
            final GitHubPageRequest request, final PaginationConfiguration config, final int maxPerPage) {
        requireNonNull(request, "request");
        requireNonNull(config, "config");

        final OptionalInt explicit = request.explicitPerPage();
        if (explicit.isEmpty()) {
            return Math.min(config.perPage(), maxPerPage);
        }

        final int value = explicit.getAsInt();
        if (value > maxPerPage) {
            throw new IllegalArgumentException("perPage must be at most " + maxPerPage);
        }
        return value;
    }

    /**
     * Copies {@code baseQuery}, writes this token's scalar query parameters, and adds {@code per_page} when
     * {@code sendPerPage} is true and the token is not {@link GitHubNoneRequest}.
     *
     * @param request pagination token
     * @param baseQuery endpoint-specific filters
     * @param perPage bound per-page size
     * @param sendPerPage whether to send {@code per_page} for non-{@code none} tokens
     * @return new unmodifiable query map
     * @throws NullPointerException if {@code request} or {@code baseQuery} is {@code null}
     */
    public static Map<String, List<String>> queryValues(
            final GitHubPageRequest request,
            final Map<String, List<String>> baseQuery,
            final int perPage,
            final boolean sendPerPage) {
        requireNonNull(request, "request");
        requireNonNull(baseQuery, "baseQuery");

        final Map<String, List<String>> query = HttpUris.copyQueryValues(baseQuery);
        final Map<String, String> token = new LinkedHashMap<>();
        request.putQueryParameters(token);
        token.forEach((name, value) -> query.put(name, List.of(value)));
        if (sendPerPage && !(request instanceof GitHubNoneRequest)) {
            query.put("per_page", List.of(String.valueOf(perPage)));
        }
        return Collections.unmodifiableMap(query);
    }
}
