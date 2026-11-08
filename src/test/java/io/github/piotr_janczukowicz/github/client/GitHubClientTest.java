package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.API_VERSION;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.SSO;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.delete;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.get;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.getUri;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.getWithoutFollowingRedirects;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.patch;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.post;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.postUri;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.put;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.redirectLocation;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.USER_AGENT;
import static io.github.piotr_janczukowicz.test.TestClients.GITHUB_BASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubForbiddenException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubUnauthorizedException;
import io.github.piotr_janczukowicz.http.Digests;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

@UnitTest
@Isolated
class GitHubClientTest {

    private static final String TARBALL_PATH = "/repos/octocat/Hello-World/tarball/main";
    private HttpTransport transport;

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @ParameterizedTest
    @MethodSource("io.github.piotr_janczukowicz.test.TestHosts#apiBases")
    void get_shouldSendHeaders_whenQueryEmpty(final URI base) throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(base), TestClients.auth())) {
            get(client, "/user");
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.GET);
            softly.assertThat(request.uri()).isEqualTo(base.resolve("user"));
            softly.assertThat(request.headers().get(AUTHORIZATION)).contains("Bearer gh-token");
            softly.assertThat(request.headers().get(ACCEPT)).contains(GitHubClient.DEFAULT_ACCEPT);
            softly.assertThat(request.headers().get(API_VERSION)).contains(GitHubClient.API_VERSION);
            softly.assertThat(request.headers().get(USER_AGENT)).contains(TestClients.USER_AGENT);
        });
    }

    @Test
    void get_shouldApplyExtraHeaders_whenHttpHeadersProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(GITHUB_BASE)
                .defaultHeaders(HttpHeaders.of("X-Custom", "from-headers"))
                .build();

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            get(client, "/user");
        }

        assertThat(captureSingleRequest().headers().get("X-Custom")).contains("from-headers");
    }

    @Test
    void get_shouldApplyOverlayHeaders_whenOverlayProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        final HttpClientConfiguration config =
                TestClients.configurationBuilder(GITHUB_BASE).build();

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            get(client, "/user", Map.of(), HttpHeaders.of(API_VERSION, "2022-11-28"));
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.headers().get(API_VERSION)).contains("2022-11-28");
    }

    @Test
    void execute_shouldReturnResponse_whenStatusIsExpected() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.of(new byte[] {1})));
        final HttpClientConfiguration config =
                TestClients.configurationBuilder(GITHUB_BASE).build();

        final HttpResponse response;
        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            response = client.execute(HttpCall.get("/missing"), Set.of(HttpStatus.NOT_FOUND.code()));
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.code());
    }

    @Test
    void execute_shouldThrow_whenStatusIsNotExpected() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FORBIDDEN.code(),
                        HttpHeaders.empty(),
                        HttpBody.of("{\"message\":\"no\"}".getBytes(StandardCharsets.UTF_8))));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            thrown = catchThrowable(() -> client.execute(HttpCall.get("/secret"), Set.of(HttpStatus.NOT_FOUND.code())));
        }

        assertThat(thrown).isInstanceOf(GitHubForbiddenException.class);
    }

    @Test
    void execute_shouldThrow_whenExpectedStatusesNull() throws IOException {
        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            thrown = catchThrowable(() -> client.execute(HttpCall.get("/user"), null));
        }

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("expectedStatuses");
        verify(transport, never()).execute(any());
    }

    @Test
    void post_shouldSendBody_whenBodyProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            post(client, "/repos", HttpBody.of(new byte[] {1, 2}));
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(1, 2);
        });
    }

    @Test
    void get_shouldAppendQuery_whenQueryProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/user", Map.of("state", "open"));
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).hasToString("https://api.github.com/user?state=open");
    }

    @Test
    void post_shouldAppendQuery_whenQueryProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            post(client, "/repos", Map.of("draft", "true"), HttpBody.of(new byte[] {1, 2}));
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(request.uri()).hasToString("https://api.github.com/repos?draft=true");
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(1, 2);
        });
    }

    @Test
    void put_shouldSendBody_whenBodyProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            put(client, "/repos", HttpBody.of(new byte[] {3}));
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.PUT);
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(3);
        });
    }

    @Test
    void put_shouldAppendQuery_whenQueryProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            put(client, "/repos", Map.of("force", "true"), HttpBody.of(new byte[] {3}));
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.PUT);
            softly.assertThat(request.uri()).hasToString("https://api.github.com/repos?force=true");
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(3);
        });
    }

    @Test
    void patch_shouldSendBody_whenBodyProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            patch(client, "/repos", HttpBody.of(new byte[] {4}));
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.PATCH);
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(4);
        });
    }

    @Test
    void patch_shouldAppendQuery_whenQueryProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            patch(client, "/repos", Map.of("draft", "false"), HttpBody.of(new byte[] {4}));
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.PATCH);
            softly.assertThat(request.uri()).hasToString("https://api.github.com/repos?draft=false");
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(4);
        });
    }

    @Test
    void delete_shouldSendDelete_whenCalled() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            delete(client, "/repos");
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
    }

    @Test
    void delete_shouldAppendQuery_whenQueryProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            delete(client, "/repos", Map.of("force", "true"));
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
            softly.assertThat(request.uri()).hasToString("https://api.github.com/repos?force=true");
        });
    }

    @Test
    void delete_shouldSendBody_whenBodyProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());
        final HttpBody body = HttpBody.of(new byte[] {1, 2});

        try (final GitHubClient client = newClient()) {
            delete(client, "/user/emails", Map.of(), body);
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
            softly.assertThat(request.body()).isEqualTo(body);
        });
    }

    @Test
    void getUri_shouldSendAbsoluteUri_whenCalled() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            getUri(client, URI.create("https://api.github.com/user?page=2"), HttpHeaders.empty());
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).hasToString("https://api.github.com/user?page=2");
    }

    @Test
    void getUri_shouldThrow_whenOriginDiffers() {
        final URI evil = URI.create("https://evil.example/user");

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get(evil);

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void get_shouldPercentEncodeQuery_whenValueContainsReservedCharacters() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/search/issues", Map.of("q", "repo:octocat/Hello-World is:open"));
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri())
                .hasToString("https://api.github.com/search/issues?q=repo%3Aoctocat%2FHello-World%20is%3Aopen");
    }

    @Test
    void get_shouldPercentEncodeQuery_whenValueContainsPlusOrUnicode() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/search", Map.of("q", "a+b café"));
        }

        assertThat(captureSingleRequest().uri()).hasToString("https://api.github.com/search?q=a%2Bb%20caf%C3%A9");
    }

    @Test
    void get_shouldRepeatBracketQuery_whenMultipleValues() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            client.execute(HttpCall.get("/issues").withQueryValues(Map.of("labels[]", List.of("bug", "help"))));
        }

        final HttpRequest request = captureSingleRequest();
        assertSoftly(softly -> {
            softly.assertThat(request.uri()).hasParameter("labels[]", "bug");
            softly.assertThat(request.uri()).hasParameter("labels[]", "help");
        });
    }

    @Test
    void get_shouldPercentEncodePath_whenSegmentHasReservedCharacters() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, PathTemplate.of("/repos/{owner}/{repo}/contents/{path}").expand("o", "r", "%#? file"));
        }

        assertThat(captureSingleRequest().uri())
                .hasToString("https://api.github.com/repos/o/r/contents/%25%23%3F%20file");
    }

    @Test
    void get_shouldPassThroughPerPage_whenQueryContainsPerPage() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/user", Map.of("per_page", "50"));
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).hasToString("https://api.github.com/user?per_page=50");
    }

    @Test
    void get_shouldOverrideDefaultHeaders_whenExtraHeadersProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        final HttpClientConfiguration config = TestClients.configurationBuilder(GITHUB_BASE)
                .defaultHeaders(HttpHeaders.of(Map.of(ACCEPT, "application/json", "X-Custom", "value")))
                .build();

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            get(client, "/user");
        }

        final HttpRequest request = captureSingleRequest();

        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(ACCEPT)).contains("application/json");
            softly.assertThat(request.headers().get("X-Custom")).contains("value");
        });
    }

    @Test
    void get_shouldResolvePathWithoutLeadingSlash_whenPathRelative() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "user");
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).hasToString("https://api.github.com/user");
    }

    @Test
    void execute_shouldUseJsonCodecConstructor_whenProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = TestGitHubClients.raw(transport, configuration(), TestClients.auth())) {
            get(client, "/user");
        }

        assertThat(captureSingleRequest().uri()).hasToString("https://api.github.com/user");
    }

    @Test
    void execute_shouldFollowCrossOrigin_whenHeadRedirects() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://example.test/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            client.execute(HttpCall.of(HttpMethod.HEAD, "/start"));
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertSoftly(softly -> {
            softly.assertThat(captor.getAllValues().get(1).headers().get(AUTHORIZATION))
                    .isEmpty();
            softly.assertThat(captor.getAllValues().get(1).method()).isEqualTo(HttpMethod.HEAD);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303})
    void execute_shouldKeepHead_whenHeadIsRedirected(final int status) throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        status,
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            client.execute(HttpCall.of(HttpMethod.HEAD, "/start"));
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(1).method()).isEqualTo(HttpMethod.HEAD);
    }

    @Test
    void builder_shouldThrow_whenDefaultHeadersSetApiVersion() {
        final HttpClientConfiguration config = TestClients.configurationBuilder(GITHUB_BASE)
                .defaultHeaders(HttpHeaders.of("x-github-api-version", "2020-01-01"))
                .build();

        final Throwable thrown = catchThrowable(() -> DefaultGitHubClient.builder(transport, config, TestClients.auth())
                .userAgent(TestClients.USER_AGENT));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultHeaders must not set X-GitHub-Api-Version");
    }

    @Test
    void builder_shouldThrow_whenDefaultHeadersSetUserAgent() {
        final HttpClientConfiguration config = TestClients.configurationBuilder(GITHUB_BASE)
                .defaultHeaders(HttpHeaders.of("user-agent", "other/1.0"))
                .build();

        final Throwable thrown =
                catchThrowable(() -> DefaultGitHubClient.builder(transport, config, TestClients.auth()));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultHeaders must not set User-Agent");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:8080/",
                "http://app.localhost/",
                "http://127.0.0.1:8080/",
                "http://[::1]:8080/",
                "https://api.github.com/"
            })
    void builder_shouldBuild_whenBaseUriSchemeAndHostVary(final String baseUri) {
        final HttpClientConfiguration config = TestClients.configuration(URI.create(baseUri));

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void build_shouldThrow_whenBaseUriIsPlainHttpOnANonLoopbackHost() {
        final DefaultGitHubClient.Builder builder = DefaultGitHubClient.builder(
                        transport,
                        TestClients.configuration(URI.create("http://api.example.test/")),
                        TestClients.auth())
                .userAgent(TestClients.USER_AGENT);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http://api.example.test/")
                .hasMessageContaining("allowInsecureHttp(true)");
    }

    @Test
    void build_shouldBuild_whenPlainHttpIsAllowedExplicitly() {
        try (final GitHubClient client = DefaultGitHubClient.builder(
                        transport,
                        TestClients.configuration(URI.create("http://api.example.test/")),
                        TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .allowInsecureHttp(true)
                .build()) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void build_shouldCloseOwnedTransport_whenPlainHttpIsRefused() {
        final DefaultGitHubClient.Builder builder = DefaultGitHubClient.builder(
                        transport,
                        TestClients.configuration(URI.create("http://api.example.test/")),
                        TestClients.auth())
                .userAgent(TestClients.USER_AGENT);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verify(transport).close();
    }

    @Test
    void build_shouldCloseTransport_whenPlainHttpIsRefused() {
        final DefaultGitHubClient.Builder builder = DefaultGitHubClient.builder(
                        transport,
                        TestClients.configuration(URI.create("http://api.example.test/")),
                        TestClients.auth())
                .userAgent(TestClients.USER_AGENT);

        catchThrowable(builder::build);

        verify(transport).close();
    }

    @Test
    void build_shouldCloseTransportOnce_whenConstructionFailsAfterChecks() {
        final DefaultGitHubClient.Builder builder = DefaultGitHubClient.builder(
                        transport, configuration(), TestClients.auth())
                .userAgent(TestClients.USER_AGENT);
        final Throwable thrown;
        try (MockedConstruction<GitHubRateLimitTracker> ignored =
                mockConstruction(GitHubRateLimitTracker.class, (tracker, context) -> {
                    throw new IllegalStateException("tracker refused");
                })) {
            thrown = catchThrowable(builder::build);
        }

        assertThat(thrown).hasCauseInstanceOf(IllegalStateException.class);
        verify(transport, times(1)).close();
    }

    @Test
    void build_shouldThrow_whenUserAgentNotSet() {
        final DefaultGitHubClient.Builder builder =
                DefaultGitHubClient.builder(transport, configuration(), TestClients.auth());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("userAgent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad\nagent", "bad\ragent", "tab\tagent", "café/1.0", "\u0001"})
    void userAgent_shouldThrow_whenValueHasCharactersOtherThanVisibleAscii(final String value) {
        final DefaultGitHubClient.Builder builder =
                DefaultGitHubClient.builder(transport, configuration(), TestClients.auth());

        final Throwable thrown = catchThrowable(() -> builder.userAgent(value));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("visible ASCII");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "LOCALHOST",
                "app.localhost",
                "127.0.0.1",
                "127.1.2.3",
                "127.255.255.255",
                "127.0.0.0",
                "[::1]",
                "[0:0:0:0:0:0:0:1]"
            })
    void isLoopbackHost_shouldBeTrue_whenHostIsLoopback(final String host) {
        assertThat(DefaultGitHubClient.isLoopbackHost(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "api.example.test",
                "127.evil.example",
                "127.999.1.1",
                "127.0.0.256",
                "127.0.0",
                "127.0.0.1.1",
                "127.01.0.1x",
                "128.0.0.1",
                "[2001:db8::1]",
                "[not-an-address]",
                "[open",
                "closed]",
                ""
            })
    void isLoopbackHost_shouldBeFalse_whenHostIsNotLoopback(final String host) {
        assertThat(DefaultGitHubClient.isLoopbackHost(host)).isFalse();
    }

    @Test
    void isLoopbackHost_shouldBeFalse_whenHostIsNull() {
        assertThat(DefaultGitHubClient.isLoopbackHost(null)).isFalse();
    }

    @Test
    void userAgent_shouldThrow_whenBlankOrNull() {
        final DefaultGitHubClient.Builder builder =
                DefaultGitHubClient.builder(transport, configuration(), TestClients.auth());

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.userAgent(" ")))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.userAgent(null))).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void get_shouldSendConfiguredUserAgent_whenUserAgentSet() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = DefaultGitHubClient.builder(transport, configuration(), TestClients.auth())
                .userAgent("my-app/2.3")
                .build()) {
            get(client, "/user");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        assertThat(captor.getValue().headers().get(USER_AGENT)).contains("my-app/2.3");
    }

    @Test
    void builder_shouldThrow_whenAuthNull() {
        final Throwable thrown = catchThrowable(() ->
                DefaultGitHubClient.builder(transport, configuration(), null).userAgent(TestClients.USER_AGENT));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("auth");
    }

    @Test
    void builder_shouldThrow_whenConfigurationNull() {
        final Throwable thrown = catchThrowable(() ->
                DefaultGitHubClient.builder(transport, null, TestClients.auth()).userAgent(TestClients.USER_AGENT));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    @Test
    void flavor_shouldBeEmpty_whenBuilderFlavorNotSet() {
        final GitHubClient client = DefaultGitHubClient.builder(transport, configuration(), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .build();

        assertThat(client.flavor()).isEmpty();
    }

    @Test
    void flavor_shouldReturnBuilderFlavor_whenSet() {
        final GitHubClient client = TestGitHubClients.flavored(transport, GitHubFlavor.GHEC);

        assertThat(client.flavor()).contains(GitHubFlavor.GHEC);
    }

    @Test
    void pagination_shouldReturnBuilderPagination_whenSet() {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(2).perPage(30).build();

        final GitHubClient client = DefaultGitHubClient.builder(transport, configuration(), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .pagination(pagination)
                .build();

        assertThat(client.pagination()).isSameAs(pagination);
    }

    @Test
    void pagination_shouldThrow_whenBuilderPaginationNull() {
        final DefaultGitHubClient.Builder builder = DefaultGitHubClient.builder(
                        transport, configuration(), TestClients.auth())
                .userAgent(TestClients.USER_AGENT);

        final Throwable thrown = catchThrowable(() -> builder.pagination(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("pagination");
    }

    @Test
    void builder_shouldThrow_whenTransportNull() {
        final Throwable thrown =
                catchThrowable(() -> DefaultGitHubClient.builder(null, configuration(), TestClients.auth())
                        .userAgent(TestClients.USER_AGENT));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("transport");
    }

    @Test
    void close_shouldCloseTransport_whenRootClientClosed() {
        final GitHubClient client = DefaultGitHubClient.builder(transport, configuration(), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .build();

        client.close();

        verify(transport).close();
    }

    @Test
    void close_shouldCloseTransportOnce_whenRootClientClosed() {
        final GitHubClient client = newClient();
        try (client) {
            verify(transport, never()).close();
        }

        verify(transport, times(1)).close();
    }

    @Test
    void execute_shouldSendCurrentToken_whenTokenUnchangedThenRotate() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());
        final Iterator<String> tokens = List.of("t1", "t1", "t2").iterator();
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, configuration(), AuthenticationProviders.bearer(tokens::next))) {
            get(client, "/user");
            get(client, "/user");
            get(client, "/user");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(3)).execute(captor.capture());
        final List<HttpHeaders> headers =
                captor.getAllValues().stream().map(HttpRequest::headers).toList();

        assertSoftly(softly -> {
            softly.assertThat(headers.getFirst().get(AUTHORIZATION)).contains("Bearer t1");
            softly.assertThat(headers.get(1).get(AUTHORIZATION)).contains("Bearer t1");
            softly.assertThat(headers.get(2).get(AUTHORIZATION)).contains("Bearer t2");
        });
    }

    @Test
    void execute_shouldSkipTransport_whenTokenBlank() throws IOException {
        final Throwable thrown;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, configuration(), AuthenticationProviders.bearer(() -> "  "))) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("token must not be blank");
        verify(transport, never()).execute(any());
    }

    @Test
    void execute_shouldSendBasicCredentials_whenClientCredentials() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client =
                TestGitHubClients.raw(transport, configuration(), AuthenticationProviders.basic("Iv1.abc", "s3cr3t"))) {
            get(client, "/user");
        }

        assertThat(captureSingleRequest().headers().get(AUTHORIZATION)).contains("Basic SXYxLmFiYzpzM2NyM3Q=");
    }

    @Test
    void execute_shouldKeyByClientIdWithoutSecret_whenClientCredentials() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client =
                TestGitHubClients.raw(transport, configuration(), AuthenticationProviders.basic("Iv1.abc", "s3cr3t"))) {
            get(client, "/user");
        }

        final HttpRequest request = captureSingleRequest();
        assertSoftly(softly -> {
            softly.assertThat(request.credentialId()).isEqualTo("basic:Iv1.abc");
            softly.assertThat(request.credentialKey()).isEqualTo("id:basic:Iv1.abc");
            softly.assertThat(request.credentialKey()).doesNotContain("s3cr3t");
            softly.assertThat(request.credentialKey()).doesNotContain("SXYxLmFiYzpzM2NyM3Q=");
        });
    }

    @Test
    void execute_shouldNotResend_whenBasicCredentialsRejected() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorizedResponse());

        final Throwable thrown;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, configuration(), AuthenticationProviders.basic("Iv1.abc", "s3cr3t"))) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void execute_shouldKeyByTokenDigest_whenBearerToken() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/user");
        }

        assertThat(captureSingleRequest().credentialId()).isEqualTo(Digests.keyedSha256Hex(TestClients.TOKEN));
    }

    @Test
    void execute_shouldSendNoAuthorization_whenNone() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client =
                TestGitHubClients.raw(transport, configuration(), AuthenticationProviders.none())) {
            get(client, "/user");
        }

        final HttpRequest request = captureSingleRequest();
        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(AUTHORIZATION)).isEmpty();
            softly.assertThat(request.credentialId()).isNull();
            softly.assertThat(request.credentialKey()).isEqualTo("anonymous");
        });
    }

    @Test
    void execute_shouldNotResend_whenUnauthenticatedRequestRejected() throws IOException {
        when(transport.execute(any())).thenReturn(unauthorizedResponse());

        final Throwable thrown;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, configuration(), AuthenticationProviders.none())) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void pagination_shouldExposeMaxPages_whenCustomConfig() {
        final Integer maxPages;
        try (final GitHubClient client = DefaultGitHubClient.builder(transport, configuration(), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .pagination(PaginationConfiguration.builder().maxPages(7).build())
                .build()) {
            maxPages = client.pagination().maxPages();
        }

        assertThat(maxPages).isEqualTo(7);
    }

    @Test
    void pagination_shouldReturnDefaults_whenBuilderDoesNotSetPagination() {
        final PaginationConfiguration pagination;
        try (final GitHubClient client = TestGitHubClients.raw(transport, configuration(), TestClients.auth())) {
            pagination = client.pagination();
        }

        assertThat(pagination).isEqualTo(PaginationConfiguration.defaults());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/users", "//evil.example/users"})
    void execute_shouldThrowAndSkipTransport_whenPathNotRelative(final String path) throws IOException {
        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get(path);

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(transport, never()).execute(any());
    }

    @Test
    void execute_shouldThrowAndSkipTransport_whenDotSegmentsEscapeBase() throws IOException {
        final HttpClientConfiguration config =
                TestClients.configurationBuilder(TestHosts.GHES).build();

        final Throwable thrown;
        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            final HttpCall call = HttpCall.get("foo/../../admin");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(transport, never()).execute(any());
    }

    @Test
    void execute_shouldSendQuery_whenPathRelative() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/users", Map.of("since", "1"));
        }

        assertThat(captureSingleRequest().uri()).hasToString("https://api.github.com/users?since=1");
    }

    @Test
    void constructor_shouldAcceptJsonCodec_whenProvided() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = TestGitHubClients.raw(transport, configuration(), TestClients.auth())) {
            get(client, "/user");
        }

        verify(transport).execute(any());
    }

    @Test
    void execute_shouldThrowAndSkipTransport_whenAbsoluteUriOffOrigin() throws IOException {
        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get(URI.create("https://evil.example/users"));

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(transport, never()).execute(any());
    }

    @Test
    void execute_shouldAppendQuery_whenAbsoluteUriHasQuery() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            getUri(client, URI.create("https://api.github.com/users"), Map.of("since", "1"), HttpHeaders.empty());
        }

        assertThat(captureSingleRequest().uri()).hasToString("https://api.github.com/users?since=1");
    }

    @Test
    void execute_shouldThrow_whenUriIsRelative() {
        final Throwable thrown = catchThrowable(() -> HttpCall.get(URI.create("/users")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
    }

    @Test
    void redirectLocation_shouldReturnLocation_whenResponseIsFound() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(
                                LOCATION, List.of("https://codeload.github.com/octocat/Hello-World/tar.gz/main"))),
                        HttpBody.absent()));

        final URI location;
        try (final GitHubClient client = newClient()) {
            location = redirectLocation(client, TARBALL_PATH, HttpHeaders.empty());
        }

        assertThat(location).hasToString("https://codeload.github.com/octocat/Hello-World/tar.gz/main");
    }

    @Test
    void redirectLocation_shouldSendSingleApiRequest_whenCalled() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(
                                LOCATION, List.of("https://codeload.github.com/octocat/Hello-World/tar.gz/main"))),
                        HttpBody.absent()));

        try (final GitHubClient client = newClient()) {
            redirectLocation(client, TARBALL_PATH, HttpHeaders.empty());
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).hasToString("https://api.github.com/repos/octocat/Hello-World/tarball/main");
    }

    @Test
    void redirectLocation_shouldThrow_whenStatusNotFound() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void redirectLocation_shouldReturnLocation_whenStatusIsTemporaryRedirect() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.TEMPORARY_REDIRECT.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("/other"))),
                        HttpBody.absent()));

        final URI location;
        try (final GitHubClient client = newClient()) {
            location = redirectLocation(client, TARBALL_PATH, HttpHeaders.empty());
        }

        assertThat(location).hasToString("https://api.github.com/other");
        verify(transport).execute(any());
    }

    @Test
    void redirectLocation_shouldThrow_whenLocationMissing() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining(LOCATION);
    }

    @Test
    void redirectLocation_shouldThrow_whenLocationSchemeIsNotHttp() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("ftp://example.com/x"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessage("redirect to unsupported scheme");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204})
    void redirectLocation_shouldThrowIllegalState_whenResponseIsSuccessful(final int status) throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(status, HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("expected a redirect");
    }

    @Test
    void redirectLocation_shouldThrowIllegalState_whenSuccessfulResponseHasLocation() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.CREATED.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://codeload.github.com/x"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("expected a redirect");
    }

    @Test
    void redirectLocation_shouldThrowForbidden_whenStatusIsForbidden() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FORBIDDEN.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://codeload.github.com/x?token=secret"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown)
                .isInstanceOf(GitHubForbiddenException.class)
                .satisfies(
                        error -> assertThat(String.valueOf(error.getMessage())).doesNotContain("token=secret"));
    }

    @Test
    void redirectLocation_shouldThrowApiException_whenStatusIsNotModified() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.NOT_MODIFIED.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://codeload.github.com/x?token=secret"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown)
                .isExactlyInstanceOf(GitHubApiException.class)
                .satisfies(
                        error -> assertThat(String.valueOf(error.getMessage())).doesNotContain("token=secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void redirectLocation_shouldThrow_whenLocationBlank(final String location) throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of(location))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 303, 307, 308})
    void redirectLocation_shouldThrow_whenLocationMissingOnRedirect(final int status) throws IOException {
        when(transport.execute(any())).thenReturn(new HttpResponse(status, HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocation(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining(LOCATION);
    }

    @Test
    void redirectLocationIfAny_shouldThrow_whenLocationMissing() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final GitHubCaller caller = TestClients.caller(client);
            final HttpCall call = HttpCall.get(TARBALL_PATH);

            thrown = catchThrowable(() -> caller.redirectLocationIfAny(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining(LOCATION);
    }

    @Test
    void postUri_shouldAllowUploadsOrigin_whenApiBaseIsGithubCom() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());
        final URI uploadUri =
                URI.create("https://uploads.github.com/repos/octocat/Hello-World/releases/1/assets?name=demo.zip");

        try (final GitHubClient client = newClient()) {
            postUri(
                    client,
                    uploadUri,
                    HttpBody.of(new byte[] {1}),
                    HttpHeaders.of(CONTENT_TYPE, "application/octet-stream"));
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).isEqualTo(uploadUri);
    }

    @Test
    void postUri_shouldRejectEvilOrigin_whenUploadsHostNotAllowed() {
        final URI evil = URI.create("https://evil.example/upload");
        final HttpBody body = HttpBody.of(new byte[] {1});

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.of(HttpMethod.POST, evil).withBody(body);

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void postUri_shouldAllowSameOrigin_whenApiBaseIsEnterpriseHost() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(
                        URI.create("https://github.example.com/api/v3/"))
                .build();
        final URI uploadUri = URI.create("https://github.example.com/api/v3/uploads/assets");

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            postUri(client, uploadUri, HttpBody.of(new byte[] {1}), HttpHeaders.empty());
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).isEqualTo(uploadUri);
    }

    @Test
    void postUri_shouldAllowUploadsOrigin_whenApiBaseIsGheHost() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(
                        URI.create("https://api.octocorp.ghe.com/"))
                .build();
        final URI uploadUri = URI.create("https://uploads.octocorp.ghe.com/repos/o/r/releases/1/assets");

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            postUri(client, uploadUri, HttpBody.of(new byte[] {1}), HttpHeaders.empty());
        }

        final HttpRequest request = captureSingleRequest();
        assertThat(request.uri()).isEqualTo(uploadUri);
    }

    @Test
    void postUri_shouldRejectUploadsGithubCom_whenApiBaseIsEnterpriseHost() {
        final HttpClientConfiguration config = TestClients.configurationBuilder(
                        URI.create("https://github.example.com/api/v3/"))
                .build();
        final URI uploadUri = URI.create("https://uploads.github.com/repos/o/r/releases/1/assets");
        final HttpBody body = HttpBody.of(new byte[] {1});

        final Throwable thrown;
        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            final HttpCall call = HttpCall.of(HttpMethod.POST, uploadUri).withBody(body);

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API base URI");
    }

    @Test
    void redirectLocation_shouldThrow_whenUriIsRelative() {
        final Throwable thrown =
                catchThrowable(() -> HttpCall.get(URI.create("repos/octocat/Hello-World/tarball/main")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
    }

    @Test
    void redirectLocation_shouldMergeOverlayHeaders_whenOverlayProvided() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(
                                LOCATION, List.of("https://codeload.github.com/octocat/Hello-World/tar.gz/main"))),
                        HttpBody.absent()));

        try (final GitHubClient client = newClient()) {
            redirectLocation(
                    client,
                    "/repos/octocat/Hello-World/tarball/main",
                    HttpHeaders.of(ACCEPT, "application/octet-stream"));
        }

        final HttpRequest request = captureSingleRequest();

        assertThat(request.headers().get(ACCEPT)).contains("application/octet-stream");
    }

    @Test
    void postUri_shouldThrow_whenUriIsRelative() {
        final Throwable thrown = catchThrowable(
                () -> HttpCall.of(HttpMethod.POST, URI.create("repos/octocat/Hello-World/releases/1/assets")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
    }

    @Test
    void post_shouldNotFollow_whenFoundRedirectWouldDowngradeToGet() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://example.test/user"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.of(HttpMethod.POST, "/user").withBody(HttpBody.of(new byte[] {1}));

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("would be sent as GET");
        assertRedirectMetadata(thrown, HttpStatus.FOUND, HttpMethod.POST, "https://api.github.com/user");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void post_shouldNotFollow_whenTemporaryRedirectIsCrossOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.TEMPORARY_REDIRECT.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://example.test/user"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.of(HttpMethod.POST, "/user").withBody(HttpBody.of(new byte[] {1}));

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown)
                .isInstanceOf(GitHubApiException.class)
                .hasMessage("cross-origin redirect not followed for POST");
        assertRedirectMetadata(thrown, HttpStatus.TEMPORARY_REDIRECT, HttpMethod.POST, "https://api.github.com/user");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void get_shouldSendOnlySafeHeaders_whenRedirectIsCrossOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://example.test/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(GITHUB_BASE)
                .defaultHeaders(HttpHeaders.of(Map.of("X-Custom", "secret")))
                .build();

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            get(client, "/user");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertSoftly(softly -> {
            softly.assertThat(captor.getAllValues().getFirst().headers().get("X-Custom"))
                    .contains("secret");
            softly.assertThat(captor.getAllValues().get(1).headers().asMap().keySet())
                    .containsExactlyInAnyOrder(ACCEPT, USER_AGENT);
        });
    }

    @Test
    void get_shouldKeepAuthorizationDropped_whenRedirectReturnsToOrigin() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://example.test/mid"))),
                        HttpBody.absent()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/start");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(3)).execute(captor.capture());
        assertThat(captor.getAllValues().get(2).headers().get(AUTHORIZATION)).isEmpty();
    }

    @Test
    void get_shouldNotFollow_whenHttpsRedirectsToHttp() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("http://api.github.com/user"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessage("https to http redirect is not followed");
        assertRedirectMetadata(thrown, HttpStatus.FOUND, HttpMethod.GET, "https://api.github.com/user");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void get_shouldFollowAsGet_whenSeeOther() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.SEE_OTHER.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/users/me");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(1).method()).isEqualTo(HttpMethod.GET);
    }

    @Test
    void post_shouldRepeatBody_whenPermanentRedirect() throws IOException {
        final List<HttpRequest> sent =
                postFollowingRedirect(HttpStatus.PERMANENT_REDIRECT, HttpBody.of(new byte[] {3}));
        assertThat(sent.get(1).method()).isEqualTo(HttpMethod.POST);
    }

    @Test
    void get_shouldTreatHttpPortAsSameOrigin_whenDefaultPortOmitted() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("http://example.test:80/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(URI.create("http://example.test/"))
                .build();

        try (final GitHubClient client = DefaultGitHubClient.builder(transport, config, TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .allowInsecureHttp(true)
                .build()) {
            get(client, "/start");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(1).headers().get(AUTHORIZATION)).contains("Bearer gh-token");
    }

    @ParameterizedTest
    @MethodSource("crossOriginRedirects")
    void get_shouldDropAuthorization_whenRedirectIsCrossOrigin(final URI base, final String location)
            throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of(location))),
                        HttpBody.absent()))
                .thenReturn(okResponse());

        try (final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(base), TestClients.auth())) {
            get(client, "/user");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(1).headers().get(AUTHORIZATION)).isEmpty();
    }

    @Test
    void get_shouldFollow_whenHttpRedirectsToHttps() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://example.test/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(URI.create("http://example.test/"))
                .build();

        try (final GitHubClient client = DefaultGitHubClient.builder(transport, config, TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .allowInsecureHttp(true)
                .build()) {
            get(client, "/user");
        }

        verify(transport, times(2)).execute(any());
    }

    @Test
    void get_shouldFollow_whenHttpRedirectsToOtherHttpHost() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("http://other.test/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(URI.create("http://example.test/"))
                .build();

        try (final GitHubClient client = DefaultGitHubClient.builder(transport, config, TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .allowInsecureHttp(true)
                .build()) {
            get(client, "/user");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(1).headers().get(AUTHORIZATION)).isEmpty();
    }

    @Test
    void get_shouldFollowSameOriginRedirect_whenMovedPermanently() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.MOVED_PERMANENTLY.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com/user"))),
                        HttpBody.absent()))
                .thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/users/me");
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertSoftly(softly -> {
            softly.assertThat(captor.getAllValues().get(1).method()).isEqualTo(HttpMethod.GET);
            softly.assertThat(captor.getAllValues().get(1).uri()).hasToString("https://api.github.com/user");
            softly.assertThat(captor.getAllValues().get(1).headers().get(AUTHORIZATION))
                    .contains("Bearer gh-token");
        });
    }

    @Test
    void post_shouldRepeatBody_whenTemporaryRedirect() throws IOException {
        final List<HttpRequest> sent =
                postFollowingRedirect(HttpStatus.TEMPORARY_REDIRECT, HttpBody.of(new byte[] {9}));
        assertSoftly(softly -> {
            softly.assertThat(sent.get(1).method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(sent.get(1).body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .containsExactly(9);
        });
    }

    @Test
    void get_shouldThrow_whenRedirectHopLimitExceeded() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com/next"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get("/start");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("hop limit");
        assertRedirectMetadata(thrown, HttpStatus.FOUND, HttpMethod.GET, "https://api.github.com/next");
        verify(transport, times(6)).execute(any());
    }

    @Test
    void get_shouldThrow_whenRedirectLocationMissing() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("missing Location");
        assertRedirectMetadata(thrown, HttpStatus.FOUND, HttpMethod.GET, "https://api.github.com/user");
    }

    @Test
    void get_shouldThrow_whenRedirectLocationMalformed() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("http://"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("malformed");
        assertRedirectMetadata(thrown, HttpStatus.FOUND, HttpMethod.GET, "https://api.github.com/user");
    }

    @Test
    void get_shouldThrow_whenRedirectLocationHasNoScheme() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("//x:99999"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("malformed");
    }

    @Test
    void get_shouldThrow_whenRedirectSchemeIsNotHttp() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("ftp://example.com/x"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessage("redirect to unsupported scheme");
        assertRedirectMetadata(thrown, HttpStatus.FOUND, HttpMethod.GET, "https://api.github.com/user");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void get_shouldThrow_whenRedirectPortOutOfRange() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com:99999/x"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            final HttpCall call = HttpCall.get("/user");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("malformed");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void getWithoutFollowingRedirects_shouldReturnResponse_whenSuccessful() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        final HttpResponse response;
        try (final GitHubClient client = newClient()) {
            response = getWithoutFollowingRedirects(client, "/orgs/o/memberships/u", Map.of(), HttpHeaders.empty());
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
    }

    @Test
    void getWithoutFollowingRedirects_shouldThrow_whenResponseIsFound() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com/other"))),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            thrown = catchThrowable(
                    () -> getWithoutFollowingRedirects(client, "/orgs/o/memberships/u", Map.of(), HttpHeaders.empty()));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void execute_shouldMapGenericApiException_whenStatusIsBadRequest() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.BAD_REQUEST.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            thrown = catchThrowable(() -> client.execute(HttpCall.get("/user")));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        assertThat(((GitHubApiException) thrown).method()).contains(HttpMethod.GET);
    }

    @Test
    void redirectLocation_shouldUseHttpRequest_whenCalled() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.FOUND.code(),
                        HttpHeaders.fromWire(Map.of(
                                LOCATION, List.of("https://codeload.github.com/octocat/Hello-World/tar.gz/main"))),
                        HttpBody.absent()));

        final URI location;
        try (final GitHubClient client = newClient()) {
            location = TestClients.caller(client).redirectLocation(HttpCall.get(TARBALL_PATH));
        }

        assertThat(location).hasToString("https://codeload.github.com/octocat/Hello-World/tar.gz/main");
    }

    @Test
    void execute_shouldMapSsoUrl_whenForbiddenHasSsoHeader() throws IOException {
        when(transport.execute(any()))
                .thenReturn(HttpResponses.of(
                        HttpStatus.FORBIDDEN.code(),
                        Map.of(SSO, List.of("required; url=https://github.com/orgs/o/sso")),
                        HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = newClient()) {
            thrown = catchThrowable(() -> client.execute(HttpCall.get("/user")));
        }

        assertThat(thrown).isInstanceOf(GitHubForbiddenException.class);
        assertThat(((GitHubForbiddenException) thrown).ssoUrl()).contains("https://github.com/orgs/o/sso");
    }

    @Test
    void get_shouldMergeHeadersInOrder_whenDefaultsExtraAndOverlayPresent() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());
        final HttpClientConfiguration config = TestClients.configurationBuilder(GITHUB_BASE)
                .defaultHeaders(HttpHeaders.of(ACCEPT, "application/json", "X-Extra", "from-config"))
                .build();

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            get(
                    client,
                    "/user",
                    Map.of(),
                    HttpHeaders.of(
                            ACCEPT,
                            "application/vnd.github+json",
                            API_VERSION,
                            "2022-11-28",
                            "X-Overlay",
                            "from-request"));
        }

        final HttpRequest request = captureSingleRequest();
        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(AUTHORIZATION)).contains("Bearer gh-token");
            softly.assertThat(request.headers().get(USER_AGENT)).contains(TestClients.USER_AGENT);
            softly.assertThat(request.headers().get(ACCEPT)).contains("application/vnd.github+json");
            softly.assertThat(request.headers().get(API_VERSION)).contains("2022-11-28");
            softly.assertThat(request.headers().get("X-Extra")).contains("from-config");
            softly.assertThat(request.headers().get("X-Overlay")).contains("from-request");
        });
    }

    @Test
    void get_shouldKeepCommaInBracketQuery_whenSingleValued() throws IOException {
        when(transport.execute(any())).thenReturn(okResponse());

        try (final GitHubClient client = newClient()) {
            get(client, "/issues", Map.of("labels[]", "a,b"));
        }

        final HttpRequest request = captureSingleRequest();
        assertSoftly(softly -> {
            softly.assertThat(request.uri()).hasParameter("labels[]", "a,b");
            softly.assertThat(request.uri().toString()).contains("labels%5B%5D=a%2Cb");
        });
    }

    @Test
    void execute_shouldNotDoubleWrap_whenRetryingTransportInjected() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), HttpBody.absent()));
        final RetryConfiguration retry = RetryConfiguration.builder()
                .fullJitter(false)
                .maxServerErrorAttempts(3)
                .build();
        final HttpClientConfiguration config =
                TestClients.configurationBuilder(GITHUB_BASE).build();

        try (final RetryingHttpTransport retrying = new RetryingHttpTransport(transport, retry);
                final GitHubClient client = TestGitHubClients.raw(retrying, config, TestClients.auth())) {
            final Throwable thrown = catchThrowable(() -> client.execute(HttpCall.get("/user")));
            assertThat(thrown).isInstanceOf(GitHubApiException.class);
        }

        verify(transport, times(3)).execute(any());
    }

    @Test
    void execute_shouldNotWrap_whenRetryOptedOut() throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        HttpStatus.SERVICE_UNAVAILABLE.code(), HttpHeaders.empty(), HttpBody.absent()));

        try (final GitHubClient client = newClient()) {
            final Throwable thrown = catchThrowable(() -> client.execute(HttpCall.get("/user")));
            assertThat(thrown).isInstanceOf(GitHubApiException.class);
        }

        verify(transport, times(1)).execute(any());
    }

    private static void assertRedirectMetadata(
            final Throwable thrown, final HttpStatus status, final HttpMethod method, final String uri) {
        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        final GitHubApiException error = (GitHubApiException) thrown;
        assertSoftly(softly -> {
            softly.assertThat(error.statusCode()).isEqualTo(status.code());
            softly.assertThat(error.method()).contains(method);
            softly.assertThat(error.uri()).contains(URI.create(uri));
            softly.assertThat(error.headers()).isNotNull();
        });
    }

    private List<HttpRequest> postFollowingRedirect(final HttpStatus redirectStatus, final HttpBody body)
            throws IOException {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(
                        redirectStatus.code(),
                        HttpHeaders.fromWire(Map.of(LOCATION, List.of("https://api.github.com/repos"))),
                        HttpBody.absent()))
                .thenReturn(new HttpResponse(HttpStatus.CREATED.code(), HttpHeaders.empty(), HttpBody.absent()));

        try (final GitHubClient client = newClient()) {
            post(client, "/repos", Map.of(), body, HttpHeaders.empty());
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void get_shouldLogPartialResultsOnce_whenResponseSaysPartialResults() throws IOException {
        final HttpHeaders headers = HttpHeaders.of(SSO, "partial-results; organizations=21955855");
        when(transport.execute(any())).thenReturn(new HttpResponse(HttpStatus.OK.code(), headers, HttpBody.absent()));

        final List<String> debugLogs;
        try (final LogCaptor logs = LogCaptor.forClass(GitHubSsoHeaders.class);
                final GitHubClient client = newClient()) {
            logs.setLogLevelToDebug();

            get(client, "/user");

            debugLogs = logs.getDebugLogs();
        }

        assertThat(debugLogs)
                .singleElement()
                .asString()
                .contains("partial results")
                .contains("1 SSO-protected organization(s)");
    }

    private static Stream<Arguments> crossOriginRedirects() {
        return TestHosts.apiBases()
                .flatMap(base -> Stream.of(
                        Arguments.of(base, "https://" + base.getHost() + ":8443/user"),
                        Arguments.of(base, "https://example.test/user"),
                        Arguments.of(base, "https://other.test/user")));
    }

    private GitHubClient newClient() {
        return TestGitHubClients.raw(transport, configuration(), TestClients.auth());
    }

    private static HttpClientConfiguration configuration() {
        return TestClients.configurationBuilder(GITHUB_BASE).build();
    }

    private static HttpResponse okResponse() {
        return new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent());
    }

    private static HttpResponse unauthorizedResponse() {
        return new HttpResponse(HttpStatus.UNAUTHORIZED.code(), HttpHeaders.empty(), HttpBody.absent());
    }

    private HttpRequest captureSingleRequest() throws IOException {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        return captor.getValue();
    }
}
