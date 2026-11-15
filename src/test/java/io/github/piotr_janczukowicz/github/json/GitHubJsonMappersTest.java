package io.github.piotr_janczukowicz.github.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.FileUtils;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

@UnitTest
class GitHubJsonMappersTest {

    @Test
    void defaults_shouldUseSnakeCase_whenDecoding() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record User(String nodeId) {}

        final User user =
                mapper.readValue(FileUtils.readString(GitHubJsonMappersTest.class, "node-id.json"), User.class);

        assertThat(user.nodeId()).isEqualTo("abc");
    }

    @Test
    void defaults_shouldIgnoreUnknownProperties_whenDecoding() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record User(String login) {}

        final User user =
                mapper.readValue(FileUtils.readString(GitHubJsonMappersTest.class, "extra-field.json"), User.class);

        assertThat(user.login()).isEqualTo("octocat");
    }

    @Test
    void defaults_shouldRejectDuplicateKeys_whenDecoding() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Typed(String type) {}

        final Throwable thrown = catchThrowable(() -> mapper.readValue("{\"type\":\"a\",\"type\":\"b\"}", Typed.class));

        assertThat(thrown).hasMessageContaining("Duplicate");
    }

    @Test
    void defaults_shouldRejectFractionalNumber_whenTargetIsInteger() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Count(int total) {}

        final Throwable thrown = catchThrowable(() -> mapper.readValue("{\"total\": 1.9}", Count.class));

        assertThat(thrown).isInstanceOf(DatabindException.class);
    }

    @Test
    void defaults_shouldBindMissingPrimitiveToZero_whenPropertyAbsent() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Id(long id) {}

        final Id decoded = mapper.readValue("{}", Id.class);

        assertThat(decoded.id()).isZero();
    }

    @Test
    void defaults_shouldCoerceJsonNumberToString_whenTargetIsString() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Count(String total) {}

        final Count count = mapper.readValue("{\"total\": 42}", Count.class);

        assertThat(count.total()).isEqualTo("42");
    }

    @Test
    void defaults_shouldCoerceJsonBooleanToString_whenTargetIsString() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Flag(String enabled) {}

        final Flag flag = mapper.readValue("{\"enabled\": true}", Flag.class);

        assertThat(flag.enabled()).isEqualTo("true");
    }

    @Test
    void defaults_shouldCoerceJsonStringToStringList_whenTargetIsList() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Labels(List<String> labels) {}

        final Labels labels = mapper.readValue("{\"labels\":\"bug\"}", Labels.class);

        assertThat(labels.labels()).containsExactly("bug");
    }

    @Test
    void defaults_shouldDecodeListAsImmutable_whenTargetIsList() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Names(List<String> names) {}
        final Names decoded = mapper.readValue("{\"names\":[\"a\",\"b\"]}", Names.class);

        final Throwable thrown = catchThrowable(() -> decoded.names().add("c"));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaults_shouldDecodeSetAsImmutable_whenTargetIsSet() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Tags(Set<String> tags) {}
        final Tags decoded = mapper.readValue("{\"tags\":[\"a\",\"b\"]}", Tags.class);

        final Throwable thrown = catchThrowable(() -> decoded.tags().add("c"));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaults_shouldDecodeMapAsImmutable_whenTargetIsMap() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Labels(Map<String, String> labels) {}
        final Labels decoded = mapper.readValue("{\"labels\":{\"a\":\"1\"}}", Labels.class);

        final Throwable thrown = catchThrowable(() -> decoded.labels().put("b", "2"));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaults_shouldKeepNullAndNullElements_whenCollectionsAreNullable() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        record Names(@Nullable List<String> names, @Nullable List<String> missing) {}

        final Names decoded = mapper.readValue("{\"names\":[\"a\",null]}", Names.class);

        assertSoftly(softly -> {
            softly.assertThat(decoded.names()).containsExactly("a", null);
            softly.assertThat(decoded.missing()).isNull();
        });
    }

    @Test
    void constructor_shouldThrow_whenInstantiatedViaReflection() throws Exception {
        final Constructor<GitHubJsonMappers> constructor = GitHubJsonMappers.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
