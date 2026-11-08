package io.github.piotr_janczukowicz.github.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.json.JsonDecodeException;
import io.github.piotr_janczukowicz.json.JsonEncodeException;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.test.model.TestStateReason;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.FileUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

@UnitTest
class GitHubJacksonCodecTest {

    @Test
    void decode_shouldDecodeSnakeCaseJson_whenCalled() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record User(String login, long id) {}

        final User user =
                codec.decode(FileUtils.readBytes(GitHubJacksonCodecTest.class, "user-octocat.json"), User.class);

        assertThat(user).isEqualTo(new User("octocat", 1L));
    }

    @Test
    void decode_shouldDecodeList_whenTypeReferenceProvided() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record User(String login, long id) {}
        final byte[] json = FileUtils.readBytes(GitHubJacksonCodecTest.class, "users-octocat-hubot.json");

        final List<User> users = codec.decode(json, JsonTypes.listOf(User.class));

        assertThat(users).containsExactly(new User("octocat", 1L), new User("hubot", 2L));
    }

    @Test
    void decode_shouldReadUnknownEnumValueAsNull_whenListItemHasNewValue() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record Item(long id, TestStateReason stateReason) {}
        final byte[] json = ("[{\"id\":1,\"state_reason\":\"completed\"},"
                        + "{\"id\":2,\"state_reason\":\"brand_new_reason\"}]")
                .getBytes(StandardCharsets.UTF_8);

        final List<Item> items = codec.decode(json, JsonTypes.listOf(Item.class));

        assertThat(items).containsExactly(new Item(1, TestStateReason.COMPLETED), new Item(2, null));
    }

    @Test
    void encode_shouldStillWriteWireValue_whenEnumProvided() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record Item(TestStateReason stateReason) {}

        final byte[] json = codec.encode(new Item(TestStateReason.NOT_PLANNED));

        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("{\"state_reason\":\"not_planned\"}");
    }

    @Test
    void fromWire_shouldStillReject_whenValueIsUnknown() {
        final Throwable thrown = catchThrowable(() -> TestStateReason.fromWire("brand_new_reason"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_shouldThrowJsonDecodeException_whenJsonIsMalformed() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record User(String login) {}

        final Throwable thrown = catchThrowable(
                () -> codec.decode("{\"login\": \"secret-token".getBytes(StandardCharsets.UTF_8), User.class));

        assertThat(thrown)
                .isInstanceOf(JsonDecodeException.class)
                .hasMessageStartingWith("Cannot decode JSON as ")
                .hasMessageNotContaining("secret-token")
                .hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void decode_shouldNameFieldButNotValue_whenValueHasWrongType() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record Counter(int count) {}

        final Throwable thrown = catchThrowable(
                () -> codec.decode("{\"count\":\"secret-value\"}".getBytes(StandardCharsets.UTF_8), Counter.class));

        assertThat(thrown)
                .isInstanceOf(JsonDecodeException.class)
                .hasMessageContaining("count")
                .hasMessageNotContaining("secret-value")
                .hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void encode_shouldThrowJsonEncodeException_whenValueCannotBeWritten() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();

        final Throwable thrown = catchThrowable(() -> codec.encode(new Unwritable()));

        assertThat(thrown)
                .isInstanceOf(JsonEncodeException.class)
                .hasMessageContaining(Unwritable.class.getName())
                .hasCauseInstanceOf(JacksonException.class);
    }

    public static final class Unwritable {
        public String getBoom() {
            throw new IllegalStateException("no value");
        }
    }

    @Test
    void decode_shouldThrowJsonDecodeException_whenTypeDoesNotFit() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();

        final Throwable thrown = catchThrowable(
                () -> codec.decode("{\"a\":1}".getBytes(StandardCharsets.UTF_8), JsonTypes.listOf(String.class)));

        assertThat(thrown).isInstanceOf(JsonDecodeException.class);
    }

    @Test
    void encode_shouldWriteSnakeCaseJson_whenRecordProvided() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record User(String nodeId) {}

        final byte[] json = codec.encode(new User("abc"));

        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("{\"node_id\":\"abc\"}");
    }

    @Test
    void encode_shouldWriteIso8601Instant_whenInstantProvided() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        record Event(Instant createdAt) {}

        final byte[] json = codec.encode(new Event(Instant.parse("2026-03-10T00:00:00Z")));

        assertThat(new String(json, StandardCharsets.UTF_8)).contains("\"created_at\":\"2026-03-10T00:00:00Z\"");
    }

    @ParameterizedTest
    @EnumSource(DecodeTarget.class)
    void decode_shouldThrow_whenJsonIsNull(final DecodeTarget target) {
        final JsonCodec codec = GitHubJsonCodecs.defaults();

        final Throwable thrown = catchThrowable(() -> target.decodeNullJson(codec));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("json");
    }

    @ParameterizedTest
    @EnumSource(DecodeTarget.class)
    void decode_shouldThrow_whenTypeIsNull(final DecodeTarget target) {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        final byte[] json = {1};

        final Throwable thrown = catchThrowable(() -> target.decodeNullType(codec, json));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("type");
    }

    @Test
    void encode_shouldThrow_whenValueIsNull() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();

        final Throwable thrown = catchThrowable(() -> codec.encode(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    enum DecodeTarget {
        CLASS {
            @Override
            void decodeNullJson(final JsonCodec codec) {
                codec.decode(null, String.class);
            }

            @Override
            void decodeNullType(final JsonCodec codec, final byte[] json) {
                codec.decode(json, (Class<String>) null);
            }
        },
        TYPE_TOKEN {
            @Override
            void decodeNullJson(final JsonCodec codec) {
                codec.decode(null, JsonTypes.of(String.class));
            }

            @Override
            void decodeNullType(final JsonCodec codec, final byte[] json) {
                codec.decode(json, (JsonType<String>) null);
            }
        };

        abstract void decodeNullJson(JsonCodec codec);

        abstract void decodeNullType(JsonCodec codec, byte[] json);
    }

    @Test
    void decode_shouldThrowIllegalState_whenJsonIsLiteralNullForClass() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        final byte[] json = "null".getBytes(StandardCharsets.UTF_8);

        final Throwable thrown = catchThrowable(() -> codec.decode(json, String.class));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("java.lang.String");
    }

    @Test
    void decode_shouldThrowIllegalState_whenJsonIsLiteralNullForJsonType() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        final byte[] json = "null".getBytes(StandardCharsets.UTF_8);

        final Throwable thrown = catchThrowable(() -> codec.decode(json, JsonTypes.listOf(String.class)));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("java.util.List<java.lang.String>");
    }

    @Test
    void decode_shouldNotCarryControlCharactersOrHugeKeys_whenPathHoldsHostileKey() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        final char backslash = (char) 92;
        final String key = "a".repeat(5000) + backslash + "n[FORGED] admin logged in" + backslash + "u0007";
        final byte[] json = ("{\"m\":{\"" + key + "\":\"not-a-number\"}}").getBytes(StandardCharsets.UTF_8);

        final Throwable thrown = catchThrowable(() -> codec.decode(json, HostileKeyPayload.class));

        assertThat(thrown)
                .isInstanceOf(JsonDecodeException.class)
                .message()
                .hasSizeLessThan(400)
                .doesNotContain("\n")
                .doesNotContain(String.valueOf((char) 7))
                .contains("InvalidFormatException");
    }

    @Test
    void decode_shouldReplaceControlCharacters_whenShortKeyHoldsLineBreak() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();
        final char backslash = (char) 92;
        final String key = "x" + backslash + "ny";
        final byte[] json = ("{\"m\":{\"" + key + "\":\"not-a-number\"}}").getBytes(StandardCharsets.UTF_8);

        final Throwable thrown = catchThrowable(() -> codec.decode(json, HostileKeyPayload.class));

        assertThat(thrown).message().doesNotContain("\n").contains("x?y");
    }

    record HostileKeyPayload(Map<String, Integer> m) {}

    @Test
    void decode_shouldThrowDecodeException_whenBodyIsEmpty() {
        final JsonCodec codec = GitHubJsonCodecs.defaults();

        final Throwable thrown = catchThrowable(() -> codec.decode(new byte[0], HostileKeyPayload.class));

        assertThat(thrown).isInstanceOf(JsonDecodeException.class);
    }

    @Test
    void decode_shouldThrowDecodeException_whenTextFollowsTheValue() {
        final byte[] json = "{\"m\":{}} garbage".getBytes(StandardCharsets.UTF_8);

        final JsonCodec codec = GitHubJsonCodecs.defaults();

        final Throwable thrown = catchThrowable(() -> codec.decode(json, HostileKeyPayload.class));

        assertThat(thrown).isInstanceOf(JsonDecodeException.class);
    }

    @Test
    void decode_shouldThrowDecodeException_whenNestingIsFarTooDeep() {
        final byte[] json = ("[".repeat(600) + "]".repeat(600)).getBytes(StandardCharsets.UTF_8);

        final JsonCodec codec = GitHubJsonCodecs.defaults();

        final Throwable thrown = catchThrowable(() -> codec.decode(json, Object.class));

        assertThat(thrown).isInstanceOf(JsonDecodeException.class);
    }

    @Test
    void decode_shouldSurfaceAsDecodeFailure_whenACustomDeserializerThrowsAnUncheckedException() {
        record Thing(String name) {}
        final JsonMapper mapper = JsonMapper.builder()
                .addModule(new SimpleModule().addDeserializer(Thing.class, new ValueDeserializer<Thing>() {
                    @Override
                    public Thing deserialize(final JsonParser parser, final DeserializationContext context) {
                        throw new IllegalStateException("rejected");
                    }
                }))
                .build();
        final GitHubJacksonCodec codec = new GitHubJacksonCodec(mapper);
        final byte[] json = "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8);

        final Throwable asClass = catchThrowable(() -> codec.decode(json, Thing.class));
        final Throwable asType = catchThrowable(() -> codec.decode(json, JsonTypes.of(Thing.class)));

        assertSoftly(softly -> {
            softly.assertThat(asClass)
                    .isInstanceOf(JsonDecodeException.class)
                    .hasMessageContaining("IllegalStateException");
            softly.assertThat(asType)
                    .isInstanceOf(JsonDecodeException.class)
                    .hasMessageContaining("IllegalStateException");
        });
    }
}
