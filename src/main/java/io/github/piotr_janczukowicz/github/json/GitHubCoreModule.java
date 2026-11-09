package io.github.piotr_janczukowicz.github.json;

import io.github.piotr_janczukowicz.github.GitHubErrorBody;
import java.time.Instant;
import tools.jackson.databind.module.SimpleModule;

/** The adapters the transport itself needs: GitHub's timestamps and its error body. */
final class GitHubCoreModule {

    private GitHubCoreModule() {
        throw new UnsupportedOperationException("Utility class");
    }

    static SimpleModule create() {
        final SimpleModule module = new SimpleModule("GitHubCore");
        module.addDeserializer(Instant.class, new GitHubInstantDeserializer());
        module.addDeserializer(GitHubErrorBody.class, new GitHubErrorBodyDeserializer());
        return module;
    }
}
