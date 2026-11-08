package io.github.piotr_janczukowicz.github.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubSearchEnvelopeTest {

    @Test
    void compactConstructor_shouldLeaveLexicalFallbackReasonsNull_whenLexicalFallbackReasonsNull() {
        final GitHubSearchEnvelope<String> envelope =
                new GitHubSearchEnvelope<>(1, false, List.of("a"), GitHubIssueSearchType.LEXICAL, null);

        assertThat(envelope.lexicalFallbackReasons()).isNull();
    }

    @Test
    void compactConstructor_shouldThrow_whenItemsNull() {
        final Throwable thrown = catchThrowable(() -> new GitHubSearchEnvelope<>(1, false, null, null, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("items");
    }
}
