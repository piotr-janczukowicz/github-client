package io.github.piotr_janczukowicz.github.client.pagination;

import io.github.piotr_janczukowicz.github.json.GitHubJsonCodecs;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.json.JsonType;

/** Decodes the list responses of a GitHub client with the default JSON codec. */
record GitHubCodecListJson() implements GitHubListSpec.ListJson {

    private static final JsonCodec CODEC = GitHubJsonCodecs.defaults();

    @Override
    public <R> R decode(final HttpResponse response, final Class<R> type) {
        return CODEC.decode(response.requireBodyBytes(), type);
    }

    @Override
    public <R> R decode(final HttpResponse response, final JsonType<R> type) {
        return CODEC.decode(response.requireBodyBytes(), type);
    }
}
