package io.github.piotr_janczukowicz.github.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@UnitTest
class GitHubInstantDeserializerTest {

    private record Stamp(Instant createdAt) {}

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2024-02-29T23:59:59Z",
                "1970-01-01T00:00:00Z",
                "2011-01-26T19:01:12Z",
                "0001-01-01T00:00:00Z",
                "9999-12-31T23:59:59Z",
                "2024-12-31T00:00:00Z",
                "2016-12-31T23:59:60Z",
                "2024-01-15T10:20:30.123Z",
                "2024-01-15T10:20:30+02:00",
                "2024-01-15T10:20:30+00:00"
            })
    void deserialize_shouldMatchInstantParse_whenTextIsValid(final String text) {
        final JsonMapper mapper = GitHubJsonMappers.defaults();

        final Stamp stamp = mapper.readValue("{\"created_at\":\"" + text + "\"}", Stamp.class);

        assertThat(stamp.createdAt()).isEqualTo(Instant.parse(text));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2023-02-29T10:00:00Z",
                "2024-13-01T10:00:00Z",
                "2024-00-10T10:00:00Z",
                "2024-04-31T10:00:00Z",
                "2024-04-00T10:00:00Z",
                "2024-04-10T24:00:01Z",
                "2024-04-10T10:60:00Z",
                "2024-04-10T10:00:61Z",
                "20x4-04-10T10:00:00Z",
                "2024-04-10 10:00:00Z",
                "not a date",
                "2024/04-10T10:00:00Z",
                "2024-04/10T10:00:00Z",
                "2024-04-10T10-00:00Z",
                "2024-04-10T10:00-00Z",
                "2024-04-10T10:00:00X",
                "20/4-04-10T10:00:00Z",
                "20:4-04-10T10:00:00Z"
            })
    void deserialize_shouldFail_whenTextIsNotAnInstant(final String text) {
        final JsonMapper mapper = GitHubJsonMappers.defaults();
        final String json = "{\"created_at\":\"" + text + "\"}";

        final Throwable thrown = catchThrowable(() -> mapper.readValue(json, Stamp.class));

        assertThat(thrown).hasMessageContaining(text);
    }

    @Test
    void deserialize_shouldReadNumericTimestamp_whenValueIsNumber() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();

        final Stamp stamp = mapper.readValue("{\"created_at\":1700000000}", Stamp.class);

        assertThat(stamp.createdAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void deserialize_shouldReturnNull_whenValueIsJsonNull() {
        final JsonMapper mapper = GitHubJsonMappers.defaults();

        final Stamp stamp = mapper.readValue("{\"created_at\":null}", Stamp.class);

        assertThat(stamp.createdAt()).isNull();
    }
}
