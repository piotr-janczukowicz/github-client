package io.github.piotr_janczukowicz.github.json;

import tools.jackson.databind.JacksonModule;

/**
 * An extension point for the default GitHub JSON codec: a library that models GitHub's payloads (such as
 * {@code github-rest-client}) provides one, and {@link GitHubJsonCodecs#defaults()} registers every module found with
 * {@link java.util.ServiceLoader}. An application that uses only the transport has none and gets the plain codec.
 */
public interface GitHubJsonModule {

    /**
     * Returns the Jackson module to register.
     *
     * @return a module with the type adapters and mixins of the library that provides it
     */
    JacksonModule module();
}
