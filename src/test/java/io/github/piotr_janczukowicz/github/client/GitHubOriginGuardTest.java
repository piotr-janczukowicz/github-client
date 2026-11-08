package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.client.HttpOriginViolationException;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubOriginGuardTest {

    private static final URI BASE = URI.create("https://api.github.com/");

    @Test
    void constructor_shouldThrow_whenBaseUriNull() {
        final Throwable thrown = catchThrowable(() -> new GitHubOriginGuard(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("baseUri");
    }

    @Test
    void constructor_shouldThrow_whenBaseUriHasNoScheme() {
        final URI bad = URI.create("not-a-url");

        final Throwable thrown = catchThrowable(() -> new GitHubOriginGuard(bad));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("scheme");
    }

    @Test
    void constructor_shouldThrow_whenBaseUriNotHttpUrl() {
        final URI bad = URI.create("ftp://example.test/");

        final Throwable thrown = catchThrowable(() -> new GitHubOriginGuard(bad));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUri")
                .hasMessageContaining("http");
    }

    @Test
    void requireTarget_shouldAllowUploadsHostOnlyForPost_whenTargetIsParsedUrl() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);
        final ParsedUrl upload = ParsedUrl.of(URI.create("https://uploads.github.com/repos/o/r/releases/1/assets"));

        assertThat(guard.requireTarget(HttpMethod.POST, upload)).isSameAs(upload);
        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, upload));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireTarget_shouldAllowApiTarget_whenTargetIsParsedUrl() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);
        final ParsedUrl api = ParsedUrl.of(URI.create("https://api.github.com/user"));

        assertThat(guard.requireTarget(HttpMethod.GET, api)).isSameAs(api);
        assertThat(guard.requireTarget(HttpMethod.POST, api)).isSameAs(api);
    }

    @Test
    void requireTarget_shouldRejectOtherHosts_whenTargetIsParsedUrl() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);

        final Throwable thrown = catchThrowable(
                () -> guard.requireTarget(HttpMethod.POST, ParsedUrl.of(URI.create("https://evil.test/x"))));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() ->
                        guard.requireTarget(HttpMethod.POST, ParsedUrl.of(URI.create("http://uploads.github.com/x")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireTarget_shouldAllowGhesUploadsPath_whenTargetIsParsedUrl() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example.test/api/v3/"));
        final ParsedUrl upload = ParsedUrl.of(URI.create("https://ghe.example.test/api/uploads/repos/o/r/assets"));

        assertThat(guard.requireTarget(HttpMethod.POST, upload)).isSameAs(upload);
        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, upload));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    void requireTarget_shouldRejectGhesManagePath_whenSameOriginForAnyMethod(final HttpMethod method) {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example.test/api/v3/"));
        final ParsedUrl manage = ParsedUrl.of(URI.create("https://ghe.example.test/manage/v1/version"));

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(method, manage));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireTarget_shouldRejectGhesManagePath_whenTargetIsUri() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example.test/api/v3/"));
        final URI uri = URI.create("https://ghe.example.test/manage/v1/config/nodes");
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, uri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireTarget_shouldReject_whenParsedUrlNull() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("url");
        assertThat(catchThrowable(() -> guard.requireTarget(HttpMethod.GET, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("url");
    }

    @Test
    void requireTarget_shouldThrow_whenUriNull() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, null, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-a-http-url",
                "https://evil.example/users",
                "http://api.github.com/users",
                "https://api.github.com:8443/users"
            })
    void requireApiTarget_shouldThrow_whenOriginDiffers(final String uri) {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);
        final URI requestUri = URI.create(uri);
        final ParsedUrl parsed = ParsedUrl.parse(requestUri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, requestUri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void requireApiTarget_shouldThrow_whenPathEscapesGhesBase() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example/api/v3/"));
        final URI requestUri = URI.create("https://ghe.example/admin");
        final ParsedUrl parsed = ParsedUrl.parse(requestUri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.GET, requestUri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void requireApiTarget_shouldReturnUri_whenPathUnderGhesBase() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example/api/v3/"));
        final URI uri = URI.create("https://ghe.example/api/v3/users");

        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final URI allowed = guard.requireTarget(HttpMethod.GET, uri, parsed).toUri();

        assertThat(allowed).isEqualTo(uri);
    }

    @ParameterizedTest
    @MethodSource("matchingOrigins")
    void requireApiTarget_shouldReturnUri_whenOriginMatches(final String base, final String requestUri) {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create(base));
        final URI uri = URI.create(requestUri);

        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final URI allowed = guard.requireTarget(HttpMethod.GET, uri, parsed).toUri();

        assertThat(allowed).isEqualTo(uri);
    }

    private static Stream<Arguments> matchingOrigins() {
        return Stream.of(
                Arguments.of("https://api.github.com/", "https://api.github.com/users?page=2"),
                Arguments.of("https://api.github.com:443/", "https://api.github.com/users"),
                Arguments.of("http://example.test:80/", "http://example.test/users"));
    }

    @ParameterizedTest
    @MethodSource("allowedUploadPosts")
    void requireTargetPost_shouldReturnUri_whenOriginAllowed(final String base, final String requestUri) {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create(base));
        final URI uri = URI.create(requestUri);

        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final URI allowed = guard.requireTarget(HttpMethod.POST, uri, parsed).toUri();

        assertThat(allowed).isEqualTo(uri);
    }

    private static Stream<Arguments> allowedUploadPosts() {
        return Stream.of(
                Arguments.of("https://api.github.com/", "https://uploads.github.com/repos/o/r/releases/1/assets"),
                Arguments.of(
                        "https://api.octocorp.ghe.com/",
                        "https://uploads.octocorp.ghe.com/repos/o/r/releases/1/assets"),
                Arguments.of("https://github.example.com/api/v3/", "https://github.example.com/api/v3/uploads/assets"),
                Arguments.of("http://api.github.com/", "http://api.github.com/repos/o/r/releases/1/assets"),
                Arguments.of("https://api.github.com:8443/", "https://api.github.com:8443/repos/o/r/releases/1/assets"),
                Arguments.of("https://api.github.com/", "https://api.github.com/repos/o/r/releases/1/assets"),
                Arguments.of(
                        "https://ghe.example/api/v3/", "https://ghe.example/api/uploads/repos/o/r/releases/1/assets"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/upload", "http://uploads.github.com/upload"})
    void requireTargetPost_shouldThrow_whenOriginNotAllowed(final String uri) {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);
        final URI requestUri = URI.create(uri);
        final ParsedUrl parsed = ParsedUrl.parse(requestUri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, requestUri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @ParameterizedTest
    @MethodSource("rejectedUploadPosts")
    void requireTargetPost_shouldThrow_whenUploadsOriginNotAllowed(final String base, final String requestUri) {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create(base));
        final URI uri = URI.create(requestUri);
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, uri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    private static Stream<Arguments> rejectedUploadPosts() {
        return Stream.of(
                Arguments.of("https://api.example.com/", "https://uploads.example.com/repos/o/r/releases/1/assets"),
                Arguments.of(
                        "https://octocorp.ghe.com/", "https://uploads.octocorp.ghe.com/repos/o/r/releases/1/assets"),
                Arguments.of(
                        "https://github.example.com/api/v3/", "https://uploads.github.com/repos/o/r/releases/1/assets"),
                Arguments.of("https://ghe.example/api/v3/", "https://ghe.example/other/"),
                Arguments.of("https://api.ghe.com/", "https://uploads.ghe.com/repos/o/r/releases/1/assets"));
    }

    @Test
    void requireTargetPost_shouldThrow_whenUriNull() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, null, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @Test
    void requireApiTarget_shouldReturnUri_whenHttpOriginMatchesOnDefaultPort() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("http://example.test/"));
        final URI uri = URI.create("http://example.test/users");

        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final URI allowed = guard.requireTarget(HttpMethod.GET, uri, parsed).toUri();

        assertThat(allowed).isEqualTo(uri);
    }

    @Test
    void requireTargetPost_shouldThrow_whenUploadsSchemeDiffersFromApiBase() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);
        final URI requestUri = URI.create("http://uploads.github.com/repos/o/r/releases/1/assets");
        final ParsedUrl parsed = ParsedUrl.parse(requestUri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, requestUri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void requireTargetPost_shouldThrow_whenUploadsPortDiffersFromApiBase() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);
        final URI requestUri = URI.create("https://uploads.github.com:8443/repos/o/r/releases/1/assets");
        final ParsedUrl parsed = ParsedUrl.parse(requestUri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, requestUri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void requireTargetPost_shouldThrow_whenGhesUploadsPathHasDotSegment() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example/api/v3/"));
        final URI uri = URI.create("https://ghe.example/api/uploads/%2e%2e/secret");
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, uri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://ghe.example/api/uploads/../../secret",
                "https://ghe.example/api/uploads/%2e%2e/%2e%2e/secret"
            })
    void requireTarget_shouldThrow_whenGhesUploadsParsedUrlEscapesViaDotSegments(final String uri) {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example/api/v3/"));
        final ParsedUrl url = ParsedUrl.of(URI.create(uri));

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, url));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void requireTargetPost_shouldThrow_whenGhesUploadsPathHasLiteralDotSegment() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(URI.create("https://ghe.example/api/v3/"));
        final URI uri = URI.create("https://ghe.example/api/uploads/../secret");
        final ParsedUrl parsed = ParsedUrl.parse(uri);

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, uri, parsed));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void requireTarget_shouldCheckLikeAnyOtherMethod_whenPostHasNoParsedUrl() {
        final GitHubOriginGuard guard = new GitHubOriginGuard(BASE);
        final URI target = URI.create("https://uploads.github.com/repos/o/r/releases/1/assets");

        final Throwable thrown = catchThrowable(() -> guard.requireTarget(HttpMethod.POST, target, null));

        assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
    }
}
