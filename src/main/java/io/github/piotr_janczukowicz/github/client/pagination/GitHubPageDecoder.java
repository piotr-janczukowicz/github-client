package io.github.piotr_janczukowicz.github.client.pagination;

import io.github.piotr_janczukowicz.http.HttpResponse;

/**
 * Decodes one GitHub list HTTP response into the items of that page (and optional wrapper {@code total_count}).
 *
 * <p>The returned items list is consumed by the caller and may be wrapped without copying.
 */
@FunctionalInterface
public interface GitHubPageDecoder<T> {

    /**
     * Decodes {@code response} into one HTTP page body.
     *
     * @param response list HTTP response
     * @return items (and optional total count) for this page
     */
    GitHubPageBody<T> decode(HttpResponse response);
}
