package io.github.piotr_janczukowicz.github.client.exception;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.REQUEST_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubApiExceptionTest {

    @Test
    void constructor_shouldExposeFields_whenCalled() {
        final GitHubApiException exception =
                new GitHubApiException(500, "Server Error", "https://docs.github.com", "raw");

        assertSoftly(softly -> {
            softly.assertThat(exception.statusCode()).isEqualTo(500);
            softly.assertThat(exception.getMessage()).isEqualTo("Server Error");
            softly.assertThat(exception.documentationUrl()).contains("https://docs.github.com");
            softly.assertThat(exception.rawBody()).contains("raw");
        });
    }

    @Test
    void constructor_shouldIgnoreBlankOptionalFields_whenCalled() {
        final GitHubApiException exception = new GitHubApiException(500, "Server Error", "  ", "");

        assertSoftly(softly -> {
            softly.assertThat(exception.documentationUrl()).isEmpty();
            softly.assertThat(exception.rawBody()).isEmpty();
        });
    }

    @Test
    void subclasses_shouldExtendApiException_whenConstructed() {
        final GitHubApiException authentication = new GitHubApiException(401, "auth", null, null);
        final GitHubForbiddenException authorization =
                new GitHubForbiddenException(403, "authz", null, null, null, HttpHeaders.empty(), null, null);
        final GitHubRateLimitException rateLimit =
                new GitHubRateLimitException(429, "rate", null, null, null, HttpHeaders.empty(), null, null);
        final GitHubValidationException validation = new GitHubValidationException(
                422, "validation", null, null, List.of(), HttpHeaders.empty(), null, null);
        final GitHubApiException notFound = new GitHubApiException(404, "missing", null, null);
        final GitHubApiException notModified = new GitHubApiException(304, "cached", null, null);

        assertSoftly(softly -> {
            softly.assertThat(authentication).isInstanceOf(GitHubApiException.class);
            softly.assertThat(authorization).isInstanceOf(GitHubApiException.class);
            softly.assertThat(rateLimit).isInstanceOf(GitHubApiException.class);
            softly.assertThat(validation).isInstanceOf(GitHubApiException.class);
            softly.assertThat(notFound).isInstanceOf(GitHubApiException.class);
            softly.assertThat(notModified).isInstanceOf(GitHubApiException.class);
        });
    }

    @Test
    void constructor_shouldCopyErrorsUnmodifiable_whenValidationException() {
        final List<GitHubValidationError> errors = new ArrayList<>();
        errors.add(new GitHubValidationError(null, "title", "missing_field", "title cannot be empty"));

        final GitHubValidationException exception = new GitHubValidationException(
                422, "Validation Failed", null, null, errors, HttpHeaders.empty(), null, null);
        errors.clear();

        assertSoftly(softly -> {
            softly.assertThat(exception.errors()).hasSize(1);
            softly.assertThat(exception.errors().getFirst())
                    .isEqualTo(new GitHubValidationError(null, "title", "missing_field", "title cannot be empty"));
        });
    }

    @Test
    void constructor_shouldThrow_whenValidationErrorsNull() {
        final Throwable thrown = catchThrowable(() -> new GitHubValidationException(
                422, "Validation Failed", null, null, null, HttpHeaders.empty(), null, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_shouldExposeRequestMetadata_whenProvided() {
        final HttpHeaders headers = HttpHeaders.of(REQUEST_ID, "abc-123");
        final GitHubApiException exception = new GitHubApiException(
                500, "Server Error", null, null, headers, HttpMethod.GET, URI.create("https://api.github.com/user"));

        assertSoftly(softly -> {
            softly.assertThat(exception.headers()).isEqualTo(headers);
            softly.assertThat(exception.requestId()).contains("abc-123");
            softly.assertThat(exception.method()).contains(HttpMethod.GET);
            softly.assertThat(exception.uri()).contains(URI.create("https://api.github.com/user"));
        });
    }

    @Test
    void constructor_shouldUseEmptyHeaders_whenHeadersNull() {
        final GitHubApiException exception = new GitHubApiException(500, "Server Error", null, null, null, null, null);

        assertSoftly(softly -> {
            softly.assertThat(exception.headers()).isEqualTo(HttpHeaders.empty());
            softly.assertThat(exception.requestId()).isEmpty();
            softly.assertThat(exception.method()).isEmpty();
            softly.assertThat(exception.uri()).isEmpty();
        });
    }

    @Test
    void headers_shouldBeEmptyButRequestIdKept_whenDeserialized() throws Exception {
        final GitHubApiException original = new GitHubApiException(
                500,
                "Server Error",
                null,
                null,
                HttpHeaders.of(REQUEST_ID, "abc-123"),
                HttpMethod.GET,
                URI.create("https://api.github.com/user"));
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        final GitHubApiException restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (GitHubApiException) in.readObject();
        }

        assertSoftly(softly -> {
            softly.assertThat(restored.headers()).isEqualTo(HttpHeaders.empty());
            softly.assertThat(restored.requestId()).contains("abc-123");
            softly.assertThat(restored.method()).contains(HttpMethod.GET);
        });
    }

    @Test
    void requestId_shouldBeEmptyAfterDeserialization_whenResponseHadNone() throws Exception {
        final GitHubApiException original = new GitHubApiException(500, "Server Error", null, null);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(((GitHubApiException) in.readObject()).requestId()).isEmpty();
        }
    }

    @Test
    void authorization_shouldExposeSsoUrl_whenProvided() {
        final GitHubForbiddenException exception = new GitHubForbiddenException(
                403, "SSO", null, null, "https://github.com/orgs/o/sso", HttpHeaders.empty(), null, null);

        assertThat(exception.ssoUrl()).contains("https://github.com/orgs/o/sso");
    }

    @Test
    void constructor_shouldTruncateMessageButKeepRawBody_whenMessageIsLong() {
        final String longMessage = "x".repeat(5000);

        final GitHubApiException exception = new GitHubApiException(500, longMessage, null, longMessage);

        assertSoftly(softly -> {
            softly.assertThat(exception.getMessage()).hasSize(1024 + "... [truncated]".length());
            softly.assertThat(exception.getMessage()).endsWith("... [truncated]");
            softly.assertThat(exception.rawBody()).contains(longMessage);
        });
    }

    @Test
    void constructor_shouldKeepMessage_whenWithinLimit() {
        final String message = "y".repeat(1024);

        assertThat(new GitHubApiException(500, message, null, null).getMessage())
                .isEqualTo(message);
    }
}
