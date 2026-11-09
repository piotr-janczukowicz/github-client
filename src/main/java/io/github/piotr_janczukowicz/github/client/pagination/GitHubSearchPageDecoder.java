package io.github.piotr_janczukowicz.github.client.pagination;

import io.github.piotr_janczukowicz.github.pagination.GitHubSearchEnvelope;
import io.github.piotr_janczukowicz.http.HttpResponse;

/** Decodes one GitHub Search API HTTP response into an envelope for that page. */
@FunctionalInterface
public interface GitHubSearchPageDecoder<T> {

    /**
     * Decodes {@code response} into one search page envelope.
     *
     * @param response search HTTP response
     * @return envelope for this page
     */
    GitHubSearchEnvelope<T> decode(HttpResponse response);
}
