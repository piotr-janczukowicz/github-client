package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.REQUEST_ID;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.SSO;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubForbiddenException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubNotFoundException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubUnauthorizedException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubValidationError;
import io.github.piotr_janczukowicz.github.client.exception.GitHubValidationException;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpErrorBodies;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.FileUtils;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubErrorsTest {

    private static final URI REQUEST_URI = URI.create("https://api.github.com/search?q=e409");

    @Test
    void toException_shouldMapStatus_whenKnownError() {
        assertSoftly(softly -> {
            softly.assertThat(exception(401, HttpHeaders.empty(), "{}")).isInstanceOf(GitHubApiException.class);
            softly.assertThat(exception(403, HttpHeaders.empty(), "{}")).isInstanceOf(GitHubForbiddenException.class);
            softly.assertThat(exception(404, HttpHeaders.empty(), "{}")).isInstanceOf(GitHubApiException.class);
            softly.assertThat(exception(304, HttpHeaders.empty(), "{}")).isInstanceOf(GitHubApiException.class);
            softly.assertThat(exception(422, HttpHeaders.empty(), "{\"errors\":[{\"field\":\"title\"}]}"))
                    .isInstanceOf(GitHubValidationException.class);
            softly.assertThat(exception(400, HttpHeaders.empty(), "{}")).isInstanceOf(GitHubApiException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307})
    void toException_shouldDropLocationHeader_whenRedirectStatus(final int status) {
        final HttpHeaders headers = HttpHeaders.empty()
                .with(LOCATION, "https://objects.example.test/file?X-Amz-Signature=secret")
                .with("X-A", "b");

        final GitHubApiException exception = exception(status, headers, "{}");

        assertThat(exception.headers()).isEqualTo(HttpHeaders.of("X-A", "b"));
    }

    @ParameterizedTest
    @MethodSource("metadataCases")
    void toException_shouldExposeRequestMetadata_whenMapped(
            final int status, final HttpHeaders headers, final String body) {
        final GitHubApiException exception = exception(status, headers, body);

        assertSoftly(softly -> {
            softly.assertThat(exception.requestId()).contains("req-1");
            softly.assertThat(exception.method()).contains(HttpMethod.GET);
            softly.assertThat(exception.uri()).contains(REQUEST_URI);
        });
    }

    @Test
    void toException_shouldParseSsoUrl_whenHeaderPresent() {
        final GitHubForbiddenException exception = (GitHubForbiddenException) exception(
                403,
                HttpHeaders.of(SSO, "required; url=https://github.com/orgs/o/sso; extra", REQUEST_ID, "req-1"),
                "{}");

        assertThat(exception.ssoUrl()).contains("https://github.com/orgs/o/sso");
    }

    @Test
    void toException_shouldParseSsoUrl_whenHeaderHasNoSemicolon() {
        final GitHubForbiddenException exception = (GitHubForbiddenException)
                exception(403, HttpHeaders.of(SSO, "url=https://github.com/orgs/o/sso"), "{}");

        assertThat(exception.ssoUrl()).contains("https://github.com/orgs/o/sso");
    }

    @Test
    void toException_shouldOmitSsoUrl_whenHeaderHasNoUrl() {
        final GitHubForbiddenException exception =
                (GitHubForbiddenException) exception(403, HttpHeaders.of(SSO, "required"), "{}");

        assertThat(exception.ssoUrl()).isEmpty();
    }

    @Test
    void toException_shouldMapRateLimit_whenRemainingIsZero() {
        final GitHubApiException exception =
                exception(403, HttpHeaders.of(RATE_LIMIT_REMAINING, "0", RATE_LIMIT_RESET, "1"), "{}");

        assertThat(exception).isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void toException_shouldMapForbidden_whenAuthenticationLockedOut() {
        final HttpHeaders headers = HttpHeaders.of(RATE_LIMIT_REMAINING, "4999", RATE_LIMIT_RESET, "1");
        final String body = "{\"message\":\"Forbidden\"}";

        final GitHubApiException exception = exception(403, headers, body);

        assertThat(exception).isInstanceOf(GitHubForbiddenException.class);
    }

    @Test
    void toException_shouldKeepAbsoluteQueryUri_whenProvided() {
        final HttpCall request = HttpCall.get("/search").withQuery(Map.of("q", "e409"));

        final GitHubApiException exception = GitHubErrors.toException(
                request.method(),
                new HttpResponse(400, HttpHeaders.empty(), HttpBody.of("{}".getBytes(StandardCharsets.UTF_8))),
                REQUEST_URI,
                Clock.systemUTC());

        assertSoftly(softly -> {
            softly.assertThat(exception.uri()).contains(REQUEST_URI);
            softly.assertThat(exception.uri().orElseThrow().isAbsolute()).isTrue();
            softly.assertThat(exception.uri().orElseThrow().getQuery()).isEqualTo("q=e409");
        });
    }

    @Test
    void toException_shouldUseAbsentBody_whenNoBytes() {
        final GitHubApiException exception = GitHubErrors.toException(
                HttpMethod.GET,
                new HttpResponse(400, HttpHeaders.empty(), HttpBody.absent()),
                REQUEST_URI,
                Clock.systemUTC());

        assertThat(exception).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void constructor_shouldBeUninstantiable() throws Exception {
        final Constructor<GitHubErrors> constructor = GitHubErrors.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    private static Stream<Arguments> metadataCases() {
        final HttpHeaders id = HttpHeaders.of(REQUEST_ID, "req-1");
        return Stream.of(
                Arguments.of(304, id, "{}"),
                Arguments.of(401, id, "{}"),
                Arguments.of(403, id, "{}"),
                Arguments.of(403, HttpHeaders.of(REQUEST_ID, "req-1", SSO, "url=https://github.com/orgs/o/sso"), "{}"),
                Arguments.of(404, id, "{}"),
                Arguments.of(422, id, "{\"errors\":[{\"field\":\"title\"}]}"),
                Arguments.of(429, id, "{}"),
                Arguments.of(500, id, "{}"));
    }

    private static GitHubApiException exception(final int status, final HttpHeaders headers, final String body) {
        return GitHubErrors.toException(
                HttpMethod.GET,
                new HttpResponse(status, headers, HttpBody.of(body.getBytes(StandardCharsets.UTF_8))),
                REQUEST_URI,
                Clock.systemUTC());
    }

    @Test
    void toException_shouldMapUnauthorizedAndNotFoundToDedicatedTypes() {
        assertSoftly(softly -> {
            softly.assertThat(exception(401, HttpHeaders.empty(), "{}"))
                    .isInstanceOf(GitHubUnauthorizedException.class);
            softly.assertThat(exception(404, HttpHeaders.empty(), "{}")).isInstanceOf(GitHubNotFoundException.class);
        });
    }

    @Test
    void toException_shouldCapRawBody_whenBodyIsHuge() {
        final String body = "x".repeat(HttpErrorBodies.MAX_RAW_BODY_CHARS + 500);

        final GitHubApiException error = exception(500, HttpHeaders.empty(), body);

        assertThat(error.rawBody())
                .hasValueSatisfying(raw -> assertThat(raw).hasSize(HttpErrorBodies.MAX_RAW_BODY_CHARS));
    }

    @Test
    void parse_shouldReturnMessageAndDocs_whenBodyValid() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "not-found.json");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 404, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("Not Found");
            softly.assertThat(parsed.documentationUrl()).isEqualTo("https://docs.github.com/rest");
            softly.assertThat(parsed.errors()).isEmpty();
            softly.assertThat(parsed.rawBody()).contains("Not Found");
        });
    }

    @Test
    void parse_shouldReturnErrorItems_whenValidationErrorsPresent() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "validation-failed-title.json");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 422, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("Validation Failed");
            softly.assertThat(parsed.errors())
                    .containsExactly(
                            new GitHubValidationError(null, "title", "missing_field", "title cannot be empty"));
        });
    }

    @Test
    void parse_shouldReturnEmptyErrors_whenErrorsArrayEmpty() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "validation-failed-empty-errors.json");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 422, StandardCharsets.UTF_8);

        assertThat(parsed.errors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank-message.json", "not-an-object.json", "json-null.json", "message-number.json"})
    void parse_shouldUseFallbackMessage_whenMessageUnusable(final String resource) {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, resource);

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 400, StandardCharsets.UTF_8);

        assertThat(parsed.message()).isEqualTo("GitHub API request failed with HTTP 400");
    }

    @Test
    void parse_shouldMapStringErrorItems_whenErrorsAreStrings() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "validation-failed-string-errors.json");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 422, StandardCharsets.UTF_8);

        assertThat(parsed.errors())
                .containsExactly(
                        new GitHubValidationError(null, null, null, "first"),
                        new GitHubValidationError(null, null, null, "second"));
    }

    @Test
    void parse_shouldKeepMessage_whenErrorsAreMixed() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "validation-failed-mixed-errors.json");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 422, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("Validation Failed");
            softly.assertThat(parsed.errors())
                    .containsExactly(
                            new GitHubValidationError(null, null, null, "plain"),
                            new GitHubValidationError("Issue", "title", "missing_field", "needed"));
        });
    }

    @Test
    void parse_shouldSkipNonObjectErrorItems_whenErrorsAreMixedTypes() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "validation-failed-skipped-errors.json");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 422, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("Validation Failed");
            softly.assertThat(parsed.errors()).containsExactly(new GitHubValidationError(null, "title", null, null));
        });
    }

    @Test
    void parse_shouldIgnoreErrors_whenErrorsIsNotArray() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "errors-not-array.json");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 422, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("Validation Failed");
            softly.assertThat(parsed.errors()).isEmpty();
        });
    }

    @Test
    void parse_shouldUseFallbackMessage_whenBodyEmpty() {
        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(new byte[0], 400, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("GitHub API request failed with HTTP 400");
            softly.assertThat(parsed.errors()).isEmpty();
        });
    }

    @Test
    void parse_shouldUseFallbackMessage_whenBodyIsNotJson() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "not-json.txt");

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 400, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("GitHub API request failed with HTTP 400");
            softly.assertThat(parsed.documentationUrl()).isNull();
            softly.assertThat(parsed.errors()).isEmpty();
            softly.assertThat(parsed.rawBody()).startsWith("not json");
        });
    }

    @Test
    void absent_shouldReturnFallbackWithoutRawBody() {
        final GitHubErrors.ParsedError parsed = GitHubErrors.absent(409);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("GitHub API request failed with HTTP 409");
            softly.assertThat(parsed.documentationUrl()).isNull();
            softly.assertThat(parsed.errors()).isEmpty();
            softly.assertThat(parsed.rawBody()).isNull();
        });
    }

    @Test
    void parse_shouldThrow_whenBytesNull() {
        final Throwable thrown = catchThrowable(() -> GitHubErrors.parse(null, 400, StandardCharsets.UTF_8));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("bytes");
    }

    @Test
    void parse_shouldThrow_whenCharsetNull() {
        final byte[] bytes = FileUtils.readBytes(GitHubErrorsTest.class, "blank-message.json");

        final Throwable thrown = catchThrowable(() -> GitHubErrors.parse(bytes, 400, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("charset");
    }

    @Test
    void parse_shouldCapRawBody_whenBodyIsHuge() {
        final byte[] bytes = "x".repeat(3 * HttpErrorBodies.MAX_RAW_BODY_CHARS).getBytes(StandardCharsets.UTF_8);

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 502, StandardCharsets.UTF_8);

        assertThat(parsed.rawBody()).hasSize(HttpErrorBodies.MAX_RAW_BODY_CHARS);
    }

    @Test
    void parse_shouldParseJson_whenBodyIsExactlyTheLimit() {
        final String json = "{\"message\":\"parsed\"}";
        final byte[] bytes =
                (json + " ".repeat(GitHubErrors.MAX_JSON_BYTES - json.length())).getBytes(StandardCharsets.UTF_8);

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 502, StandardCharsets.UTF_8);

        assertThat(bytes).hasSize(GitHubErrors.MAX_JSON_BYTES);
        assertThat(parsed.message()).isEqualTo("parsed");
    }

    @Test
    void parse_shouldNotParseJson_whenBodyIsLargerThanTheLimit() {
        final byte[] bytes =
                ("{\"message\":\"parsed\"}" + " ".repeat(GitHubErrors.MAX_JSON_BYTES)).getBytes(StandardCharsets.UTF_8);

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 502, StandardCharsets.UTF_8);

        assertSoftly(softly -> {
            softly.assertThat(parsed.message()).isEqualTo("GitHub API request failed with HTTP 502");
            softly.assertThat(parsed.rawBody()).startsWith("{\"message\":\"parsed\"}");
        });
    }

    @Test
    void parse_shouldDecodeRawBody_whenCharsetGiven() {
        final byte[] bytes = "café".getBytes(StandardCharsets.ISO_8859_1);

        final GitHubErrors.ParsedError parsed = GitHubErrors.parse(bytes, 502, StandardCharsets.ISO_8859_1);

        assertThat(parsed.rawBody()).isEqualTo("café");
    }
}
