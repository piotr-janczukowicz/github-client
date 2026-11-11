package io.github.piotr_janczukowicz.github.json;

import io.github.piotr_janczukowicz.github.GitHubErrorBody;
import tools.jackson.databind.module.SimpleModule;

/** The adapter the transport itself needs: GitHub's error body. */
final class GitHubCoreModule {

    private GitHubCoreModule() {
        throw new UnsupportedOperationException("Utility class");
    }

    static SimpleModule create() {
        final SimpleModule module = new SimpleModule("GitHubCore");
        module.addDeserializer(GitHubErrorBody.class, new GitHubErrorBodyDeserializer());
        return module;
    }
}
