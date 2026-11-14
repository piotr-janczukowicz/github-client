package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.uri.HttpUris;
import java.util.List;
import java.util.Map;

/**
 * Where a page walk starts: the endpoint, its own filters, and the per-request headers.
 *
 * @param path endpoint path relative to the API base URI
 * @param query endpoint-specific filters; the pager copies them and adds the pagination parameters
 * @param overlayHeaders headers merged last onto the client's headers, on every request of the walk
 */
public record GitHubPageTarget(String path, Map<String, List<String>> query, HttpHeaders overlayHeaders) {

    public GitHubPageTarget {
        requireNonNull(path, "path");
        // Insertion order is kept: it decides the order of the parameters in the URL.
        query = HttpUris.unmodifiableQueryValues(requireNonNull(query, "query"));
        requireNonNull(overlayHeaders, "overlayHeaders");
    }
}
