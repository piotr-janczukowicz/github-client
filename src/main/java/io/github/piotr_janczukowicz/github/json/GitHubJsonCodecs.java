package io.github.piotr_janczukowicz.github.json;

import io.github.piotr_janczukowicz.json.JsonCodec;

/**
 * Factory for the {@link JsonCodec} that reads and writes GitHub's JSON.
 *
 * <p>{@link #defaults()} uses snake_case property names, ignores unknown properties, and writes
 * {@link java.time.Instant} as ISO-8601. Decode failures surface as
 * {@link io.github.piotr_janczukowicz.json.JsonDecodeException}.
 */
public final class GitHubJsonCodecs {

    private static final JsonCodec DEFAULTS = new GitHubJacksonCodec(GitHubJsonMappers.defaults());

    private GitHubJsonCodecs() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Shared codec with snake_case property names that ignores unknown properties.
     *
     * @return the default codec singleton
     */
    public static JsonCodec defaults() {
        return DEFAULTS;
    }
}
