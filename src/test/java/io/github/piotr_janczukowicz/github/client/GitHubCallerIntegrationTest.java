package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ETAG;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_MODIFIED_SINCE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_NONE_MATCH;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpStatus.ACCEPTED;
import static io.github.piotr_janczukowicz.http.HttpStatus.NOT_FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.NOT_MODIFIED;
import static io.github.piotr_janczukowicz.http.HttpStatus.NO_CONTENT;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpEmptyResponseException;
import io.github.piotr_janczukowicz.http.client.HttpResult;
import io.github.piotr_janczukowicz.json.JsonDecodeException;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.test.model.TestStateReason;
import io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@IntegrationTest
class GitHubCallerIntegrationTest {

    private static final String PATH = "/thing";
    private static final String ETAG_VALUE = "W/\"abc123\"";

    @RegisterExtension
    static final StubHttpServer HTTP = new StubHttpServer();

    @TempDir
    Path tempDir;

    record Thing(String name) {}

    record Item(long id, TestStateReason stateReason) {}

    @Test
    void fetch_shouldThrowEmptyResponseException_whenAccepted() {
        HTTP.stub(GET, PATH, status(ACCEPTED));

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(() -> client.caller().fetch(HttpCall.get(PATH), Thing.class));
        }

        assertThat(thrown)
                .isInstanceOf(HttpEmptyResponseException.class)
                .isNotInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 202")
                .hasMessageContaining(PATH);
        assertThat(((HttpEmptyResponseException) thrown).statusCode()).isEqualTo(ACCEPTED.code());
    }

    @Test
    void fetch_shouldThrowEmptyResponseException_whenNoContent() {
        HTTP.stub(GET, PATH, status(NO_CONTENT));

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(() -> client.caller().fetch(HttpCall.get(PATH), Thing.class));
        }

        assertThat(thrown).isInstanceOf(HttpEmptyResponseException.class);
    }

    @Test
    void fetchOptional_shouldBeEmpty_whenAcceptedOrNoContent() throws Exception {
        HTTP.stub(GET, "/accepted", status(ACCEPTED));
        HTTP.stub(GET, "/no-content", status(NO_CONTENT));

        final Optional<Thing> accepted;
        final Optional<Thing> noContent;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            accepted = client.caller().fetchOptional(HttpCall.get("/accepted"), Thing.class);
            noContent = client.caller().fetchOptional(HttpCall.get("/no-content"), Thing.class);
        }

        assertSoftly(softly -> {
            softly.assertThat(accepted).isEmpty();
            softly.assertThat(noContent).isEmpty();
        });
    }

    @Test
    void fetchOptional_shouldDecode_whenBodyPresent() throws Exception {
        HTTP.stub(GET, PATH, status(OK).header(CONTENT_TYPE, "application/json").body(json("{\"name\":\"a\"}")));

        final Optional<Thing> thing;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thing = client.caller().fetchOptional(HttpCall.get(PATH), Thing.class);
        }

        assertThat(thing).contains(new Thing("a"));
    }

    @Test
    void fetchOptional_shouldMapError_whenNotFound() {
        HTTP.stub(GET, PATH, status(NOT_FOUND));

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(() -> client.caller().fetchOptional(HttpCall.get(PATH), Thing.class));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void fetchOptional_shouldSendHeaderAndReturnEmpty_whenNotModified() throws Exception {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED));

        final Optional<Thing> thing;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thing = client.caller()
                    .fetchOptional(HttpCall.get(PATH).withHeader(IF_NONE_MATCH, ETAG_VALUE), Thing.class);
        }

        assertThat(thing).isEmpty();
        HTTP.verifyHeader(GET, PATH, IF_NONE_MATCH, ETAG_VALUE);
    }

    @Test
    void send_shouldReturnNotModified_whenListedInExpectedStatuses() throws Exception {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED).header(ETAG, ETAG_VALUE));

        final int statusCode;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            statusCode = client.caller()
                    .send(HttpCall.get(PATH).withHeader(IF_NONE_MATCH, ETAG_VALUE), Set.of(NOT_MODIFIED.code()))
                    .statusCode();
        }

        assertThat(statusCode).isEqualTo(NOT_MODIFIED.code());
    }

    @Test
    void send_shouldThrow_whenNotModifiedWithoutConditionalHeader() {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED));

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(() -> client.caller().send(HttpCall.get(PATH)));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void fetch_shouldThrowApiException_whenNotModifiedIsNotExpected() {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED));

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(
                    () -> client.caller().fetch(HttpCall.get(PATH).withHeader(IF_NONE_MATCH, ETAG_VALUE), Thing.class));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void fetchResult_shouldExposeEtag_whenBodyPresent() throws Exception {
        HTTP.stub(
                GET,
                PATH,
                status(OK)
                        .header(CONTENT_TYPE, "application/json")
                        .header(ETAG, ETAG_VALUE)
                        .body(json("{\"name\":\"a\"}")));

        final HttpResult<Thing> result;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            result = client.caller().fetchResult(HttpCall.get(PATH), Thing.class);
        }

        assertSoftly(softly -> {
            softly.assertThat(result.value()).contains(new Thing("a"));
            final String stablePrefixOfGzipSuffixedEtag = "W/\"abc123";
            softly.assertThat(result.etag())
                    .hasValueSatisfying(etag -> assertThat(etag).startsWith(stablePrefixOfGzipSuffixedEtag));
            softly.assertThat(result.isNotModified()).isFalse();
        });
    }

    @Test
    void fetchResult_shouldExposeEtagAndNotModified_whenConditionalHits() throws Exception {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED).header(ETAG, ETAG_VALUE));

        final HttpResult<Thing> result;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            result = client.caller().fetchResult(HttpCall.get(PATH).withHeader(IF_NONE_MATCH, ETAG_VALUE), Thing.class);
        }

        assertSoftly(softly -> {
            softly.assertThat(result.value()).isEmpty();
            softly.assertThat(result.etag()).contains(ETAG_VALUE);
            softly.assertThat(result.isNotModified()).isTrue();
        });
    }

    @Test
    void fetchOptional_shouldSendIfModifiedSinceVerbatim_whenHeaderSet() throws Exception {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED));
        final String since = "Wed, 21 Oct 2015 07:28:00 GMT";

        final Optional<Thing> thing;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thing = client.caller().fetchOptional(HttpCall.get(PATH).withHeader(IF_MODIFIED_SINCE, since), Thing.class);
        }

        assertThat(thing).isEmpty();
        HTTP.verifyHeader(GET, PATH, IF_MODIFIED_SINCE, since);
    }

    @Test
    void fetchOrEmpty_shouldFail_whenNotModifiedWithoutConditionalHeader() {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED));

        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            final Throwable thrown =
                    catchThrowable(() -> client.caller().fetchOrEmpty(HttpCall.get(PATH), Thing.class));

            assertThat(thrown).isInstanceOf(GitHubApiException.class);
        }
    }

    @Test
    void fetch_shouldDecodeListWithUnknownEnumValue_whenGitHubAddsNewValue() throws Exception {
        HTTP.stub(
                GET,
                PATH,
                status(OK)
                        .header(CONTENT_TYPE, "application/json")
                        .body(json("[{\"id\":1,\"state_reason\":\"completed\"},"
                                + "{\"id\":2,\"state_reason\":\"brand_new_reason\"}]")));

        final List<Item> items;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            items = client.caller().fetch(HttpCall.get(PATH), JsonTypes.listOf(Item.class));
        }

        assertThat(items).containsExactly(new Item(1, TestStateReason.COMPLETED), new Item(2, null));
    }

    @Test
    void fetch_shouldThrowJsonDecodeException_whenBodyIsMalformed() {
        HTTP.stub(GET, PATH, status(OK).header(CONTENT_TYPE, "application/json").body(json("{\"name\": ")));

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(() -> client.caller().fetch(HttpCall.get(PATH), Thing.class));
        }

        assertThat(thrown).isInstanceOf(JsonDecodeException.class);
    }

    @Test
    void fetchOptional_shouldDecodeTypedList_whenBodyPresent() throws Exception {
        HTTP.stub(GET, PATH, status(OK).header(CONTENT_TYPE, "application/json").body(json("[{\"name\":\"a\"}]")));

        final Optional<List<Thing>> things;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            things = client.caller().fetchOptional(HttpCall.get(PATH), JsonTypes.listOf(Thing.class));
        }

        assertThat(things).contains(List.of(new Thing("a")));
    }

    @Test
    void fetchOptional_shouldBeEmpty_whenOkResponseHasNoBody() throws Exception {
        HTTP.stub(GET, PATH, status(OK));

        final Optional<Thing> thing;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thing = client.caller().fetchOptional(HttpCall.get(PATH), Thing.class);
        }

        assertThat(thing).isEmpty();
    }

    @Test
    void fetchResult_shouldDecodeTypedList_whenConditionalHitsOrMisses() throws Exception {
        HTTP.stub(GET, PATH, status(OK).header(CONTENT_TYPE, "application/json").body(json("[{\"name\":\"a\"}]")));
        final HttpResult<List<Thing>> result;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            result = client.caller().fetchResult(HttpCall.get(PATH), JsonTypes.listOf(Thing.class));
        }
        assertThat(result.value()).contains(List.of(new Thing("a")));

        HTTP.stub(GET, PATH, status(NOT_MODIFIED));
        final HttpResult<List<Thing>> notModified;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            notModified = client.caller()
                    .fetchResult(
                            HttpCall.get(PATH).withHeader(IF_NONE_MATCH, ETAG_VALUE), JsonTypes.listOf(Thing.class));
        }
        assertThat(notModified.isNotModified()).isTrue();
    }

    @Test
    void download_shouldWriteBodyToStream_whenResponseIsSuccessful() throws Exception {
        HTTP.stub(GET, PATH, status(OK).body(new byte[] {1, 2, 3, 4}));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final long written;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            written = client.caller().download(HttpCall.get(PATH), out);
        }

        assertThat(written).isEqualTo(4);
        assertThat(out.toByteArray()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void download_shouldWriteFileAtomically_whenResponseIsSuccessful() throws Exception {
        HTTP.stub(GET, PATH, status(OK).body(new byte[] {1, 2, 3, 4}));
        final Path target = tempDir.resolve("out.bin");
        Files.write(target, new byte[] {9});

        final Path result;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            result = client.caller().download(HttpCall.get(PATH), target);
        }

        assertThat(result).isEqualTo(target);
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3, 4);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void download_shouldCreateNoFile_whenResponseIsNotFound() throws Exception {
        HTTP.stub(GET, PATH, status(NOT_FOUND).body(json("{\"message\":\"Not Found\"}")));
        final Path target = tempDir.resolve("out.bin");

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(() -> client.caller().download(HttpCall.get(PATH), target));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessage("Not Found");
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void download_shouldFail_whenConditionalCallGetsNotModified() {
        HTTP.stub(GET, PATH, status(NOT_MODIFIED).header(ETAG, ETAG_VALUE));
        final Path target = tempDir.resolve("out.bin");

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(
                    () -> client.caller().download(HttpCall.get(PATH).withHeader(IF_NONE_MATCH, ETAG_VALUE), target));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    void download_shouldRemoveTemporaryFile_whenMoveFails() throws Exception {
        HTTP.stub(GET, PATH, status(OK).body(new byte[] {1, 2, 3, 4}));
        final Path target = tempDir.resolve("occupied");
        Files.createDirectory(target);
        Files.write(target.resolve("child"), new byte[] {1});

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            thrown = catchThrowable(() -> client.caller().download(HttpCall.get(PATH), target));
        }

        assertThat(thrown).isInstanceOf(IOException.class);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).containsExactly(target);
        }
    }

    private static byte[] json(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
