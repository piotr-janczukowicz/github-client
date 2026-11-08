package io.github.piotr_janczukowicz.github.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.GitHubErrorBody;
import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubErrorBodyDeserializerTest {

    private static final JsonCodec CODEC = GitHubJsonCodecs.defaults();

    @Test
    void decode_shouldMapMessageDocsAndObjectError_whenBodyValid() {
        final byte[] json = """
                {"message":"Validation Failed","documentation_url":"https://docs.github.com/rest",
                "errors":[{"resource":"Issue","field":"title","code":"missing_field","message":"needed"}]}
                """.getBytes(StandardCharsets.UTF_8);

        final GitHubErrorBody body = CODEC.decode(json, GitHubErrorBody.class);

        assertSoftly(softly -> {
            softly.assertThat(body.message()).isEqualTo("Validation Failed");
            softly.assertThat(body.documentationUrl()).isEqualTo("https://docs.github.com/rest");
            softly.assertThat(body.errors())
                    .containsExactly(new GitHubErrorBody.Item("Issue", "title", "missing_field", "needed"));
        });
    }

    @Test
    void decode_shouldMapStringErrorsAndSkipOthers_whenErrorsMixed() {
        final byte[] json = """
                {"message":"Validation Failed","errors":["plain",1,null,{"field":"title"}]}
                """.getBytes(StandardCharsets.UTF_8);

        final GitHubErrorBody body = CODEC.decode(json, GitHubErrorBody.class);

        assertThat(body.errors())
                .containsExactly(
                        new GitHubErrorBody.Item(null, null, null, "plain"),
                        new GitHubErrorBody.Item(null, "title", null, null));
    }

    @Test
    void decode_shouldUseEmptyErrors_whenErrorsNotArray() {
        final byte[] json =
                "{\"message\":\"Validation Failed\",\"errors\":{\"field\":\"title\"}}".getBytes(StandardCharsets.UTF_8);

        final GitHubErrorBody body = CODEC.decode(json, GitHubErrorBody.class);

        assertThat(body.errors()).isEmpty();
    }

    @Test
    void decode_shouldThrow_whenRootIsNotObject() {
        final byte[] json = "[\"oops\"]".getBytes(StandardCharsets.UTF_8);

        final Throwable thrown = catchThrowable(() -> CODEC.decode(json, GitHubErrorBody.class));

        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }
}
