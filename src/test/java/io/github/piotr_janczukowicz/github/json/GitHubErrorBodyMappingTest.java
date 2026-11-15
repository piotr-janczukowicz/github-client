package io.github.piotr_janczukowicz.github.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.GitHubErrorBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubErrorBodyMappingTest extends GitHubMappingTest {

    @Test
    void decode_shouldMapMessageDocsAndObjectError_whenBodyValid() {
        final GitHubErrorBody body = decode("error-body-valid.json", GitHubErrorBody.class);

        assertSoftly(softly -> {
            softly.assertThat(body.message()).isEqualTo("Validation Failed");
            softly.assertThat(body.documentationUrl()).isEqualTo("https://docs.github.com/rest");
            softly.assertThat(body.errors())
                    .containsExactly(new GitHubErrorBody.Item("Issue", "title", "missing_field", "needed"));
        });
    }

    @Test
    void decode_shouldMapStringErrorsAndSkipOthers_whenErrorsMixed() {
        final GitHubErrorBody body = decode("error-body-errors-mixed.json", GitHubErrorBody.class);

        assertThat(body.errors())
                .containsExactly(
                        new GitHubErrorBody.Item(null, null, null, "plain"),
                        new GitHubErrorBody.Item(null, "title", null, null));
    }

    @Test
    void decode_shouldUseEmptyErrors_whenErrorsNotArray() {
        final GitHubErrorBody body = decode("error-body-errors-not-array.json", GitHubErrorBody.class);

        assertThat(body.errors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"error-body-array.json"})
    void decode_shouldThrow_whenRootIsNotObject(final String resource) {
        final Throwable thrown = catchThrowable(() -> decode(resource, GitHubErrorBody.class));

        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }
}
