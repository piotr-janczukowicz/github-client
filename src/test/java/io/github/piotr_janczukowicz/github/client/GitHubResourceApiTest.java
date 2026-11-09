package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.API_VERSION;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.MARKDOWN;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.MARKDOWN_RAW;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.REPOS;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.REPOS_OCTOCAT_TARBALL;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.REPOS_ONE;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.SEARCH_TOPICS;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.TOPIC_ENVELOPE;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.UPLOAD_URI;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.USER;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.USERS;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.json;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.okResponse;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.singleRequest;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_NONE_MATCH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.pagination.GitHubListSpec;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageNumberRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.test.model.TestUser;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

@UnitTest
class GitHubResourceApiTest {

    private static final PathTemplate RELEASE_ASSETS = PathTemplate.of("/repos/octocat/Hello-World/releases/1/assets");
    private static final PathTemplate TEAM_REPO = PathTemplate.of("/orgs/o/teams/t/repos/ow/r");
    private static final PathTemplate USER_EMAILS = PathTemplate.of("/user/emails");

    private final GitHubCallerFixture.Wiring wiring = GitHubCallerFixture.mocked();
    private final HttpTransport transport = wiring.transport();
    private final GitHubCaller caller = wiring.caller();
    private final TestApi api = wiring.api();

    @ParameterizedTest
    @MethodSource("versionHeaderCalls")
    void request_shouldSendVersionHeader_whenCalled(final VersionHeaderCall versionHeaderCall) throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        versionHeaderCall.execute(api, caller);

        assertVersionHeader(singleRequest(transport));
    }

    @Test
    void search_shouldDelegateToGitHubPageLoad_whenInvoked() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                        {"total_count":1,"incomplete_results":false,"items":[{"name":"ruby"}]}
                        """));

        final GitHubPage<String> result = caller.search(
                api.get(SEARCH_TOPICS).withQuery(Map.of("q", "ruby")),
                GitHubPageNumberRequest.of(1, 1),
                JsonTypes.of(JsonNode.class),
                TOPIC_ENVELOPE);

        assertThat(result.items()).isNotEmpty();
    }

    @Test
    void search_shouldSendOverlayAcceptAndVersionHeader_whenOverlayHeadersProvided() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                        {"total_count":1,"incomplete_results":false,"items":[{"name":"ruby"}]}
                        """));

        caller.search(
                api.get(SEARCH_TOPICS)
                        .withQuery(Map.of("q", "ruby"))
                        .withHeader(ACCEPT, "application/vnd.github.text-match+json"),
                GitHubPageNumberRequest.of(1, 1),
                JsonTypes.of(JsonNode.class),
                TOPIC_ENVELOPE);

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(ACCEPT)).contains("application/vnd.github.text-match+json");
            softly.assertThat(request.headers().get(API_VERSION)).contains(GitHubClient.API_VERSION);
        });
    }

    @Test
    void list_shouldSendVersionHeader_whenCalled() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("empty-array.json")));
        final GitHubPageRequest request = GitHubPageRequest.byPage();
        final GitHubListSpec<Object> spec = GitHubListSpec.pages(JsonTypes.listOf(Object.class));

        api.list(USERS, Map.of(), request, spec);

        assertVersionHeader(singleRequest(transport));
    }

    @Test
    void list_shouldMergeOverlayHeaders_whenOptionsProvideAccept() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("empty-array.json")));
        final GitHubPageRequest request = GitHubPageRequest.byPage();
        final GitHubListSpec<Object> spec = GitHubListSpec.pages(JsonTypes.listOf(Object.class));
        final HttpHeaders overlay = HttpHeaders.of(ACCEPT, "application/vnd.github.star+json");

        api.list(USERS, Map.of(), request, spec, overlay);

        final HttpRequest sent = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(sent.headers().get(ACCEPT)).contains("application/vnd.github.star+json");
            softly.assertThat(sent.headers().get(API_VERSION)).contains(GitHubClient.API_VERSION);
        });
    }

    @Test
    void list_shouldThrow_whenPathNull() {
        final GitHubPageRequest request = GitHubPageRequest.byPage();
        final GitHubListSpec<Object> spec = GitHubListSpec.pages(JsonTypes.listOf(Object.class));

        final Throwable thrown = catchThrowable(() -> api.list(null, Map.of(), request, spec, HttpHeaders.empty()));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("path");
    }

    @Test
    void list_shouldThrow_whenRequestNull() {
        final GitHubListSpec<Object> spec = GitHubListSpec.pages(JsonTypes.listOf(Object.class));

        final Throwable thrown = catchThrowable(() -> api.list(USERS, Map.of(), null, spec, HttpHeaders.empty()));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
    }

    @Test
    void list_shouldThrow_whenSpecNull() {
        final GitHubPageRequest request = GitHubPageRequest.byPage();

        final Throwable thrown = catchThrowable(() -> api.list(USERS, Map.of(), request, null, HttpHeaders.empty()));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("spec");
    }

    @Test
    void constructor_shouldAcceptCustomDecoder_whenProvided() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(api.get(USER));

        assertVersionHeader(singleRequest(transport));
    }

    @Test
    void constructor_shouldThrow_whenCallerNull() {
        final Throwable thrown = catchThrowable(() -> new TestApi(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("caller");
    }

    @Test
    void decode_shouldDecodeJson_whenClassProvided() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("public-user-octocat.json")));

        final TestUser user = caller.fetch(HttpCall.get("/users/octocat"), TestUser.class);

        assertThat(user).isEqualTo(TestUser.OCTOCAT);
    }

    @Test
    void decode_shouldDecodeJsonArray_whenTypeReferenceProvided() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("empty-array.json")));

        final List<TestUser> users = caller.fetch(HttpCall.get("/users"), JsonTypes.listOf(TestUser.class));

        assertThat(users).isEmpty();
    }

    @Test
    void delete_shouldSendJsonContentType_whenBodyPresent() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final Map<String, Object> body = Map.of("emails", List.of("a@b.c"));

        caller.send(caller.json(api.delete(USER_EMAILS), body));

        assertThat(singleRequest(transport).headers().get(CONTENT_TYPE)).contains("application/json");
    }

    @Test
    void patch_shouldSendEncodedJsonBody_whenValueProvided() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        record User(String nodeId) {}

        caller.send(caller.json(api.patch(USER), new User("abc")));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("application/json");
            softly.assertThat(new String(
                            request.body().mapBytes(bytes -> bytes, () -> new byte[0]), StandardCharsets.UTF_8))
                    .isEqualTo("{\"node_id\":\"abc\"}");
        });
    }

    @Test
    void get_shouldSendOverlayAcceptAndVersionHeader_whenOverlayHeadersProvided() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(
                api.get(TEAM_REPO).withHeaders(HttpHeaders.of(ACCEPT, "application/vnd.github.v3.repository+json")));

        final HttpRequest request = singleRequest(transport);

        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(ACCEPT)).contains("application/vnd.github.v3.repository+json");
            softly.assertThat(request.headers().get(API_VERSION)).contains(GitHubClient.API_VERSION);
        });
    }

    @Test
    void get_shouldThrow_whenOverlayHeadersNull() {
        final Throwable thrown = catchThrowable(() -> api.get(USER).withHeaders(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void search_shouldThrow_whenOverlayHeadersNull() {
        final Throwable thrown = catchThrowable(
                () -> api.get(SEARCH_TOPICS).withQuery(Map.of("q", "ruby")).withHeaders(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void get_shouldOmitJsonContentType_whenNoBody() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(api.get(USER));

        assertThat(singleRequest(transport).headers().get(CONTENT_TYPE)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("absentBodyCalls")
    void request_shouldOmitJsonContentType_whenBodyAbsent(final VersionHeaderCall versionHeaderCall) throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        versionHeaderCall.execute(api, caller);

        assertThat(singleRequest(transport).headers().get(CONTENT_TYPE)).isEmpty();
    }

    @Test
    void post_shouldThrow_whenBodyNull() {
        final Throwable thrown = catchThrowable(() -> caller.json(api.post(REPOS), null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void post_shouldSendRawBodyAndOverlay_whenHttpBodyAndHeadersGiven() throws Exception {
        when(transport.execute(any())).thenReturn(ok("<p>ok</p>"));
        final HttpBody body = HttpBody.of("Hello".getBytes(StandardCharsets.UTF_8));
        final HttpHeaders overlay = HttpHeaders.of(CONTENT_TYPE, "text/plain");

        caller.send(api.post(MARKDOWN_RAW).withBody(body).withHeaders(overlay));

        final HttpRequest request = singleRequest(transport);

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(request.uri().toString()).endsWith("/markdown/raw");
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("text/plain");
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .isEqualTo("Hello".getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void post_shouldSendJsonAndIfNoneMatch_whenObjectBodyAndOverlayGiven() throws Exception {
        when(transport.execute(any())).thenReturn(ok("<p>ok</p>"));
        final Map<String, String> payload = Map.of("text", "Hi");
        final HttpHeaders overlay = HttpHeaders.of(IF_NONE_MATCH, "\"abc\"");

        caller.send(caller.json(api.post(MARKDOWN), payload).withHeaders(overlay));

        final HttpRequest request = singleRequest(transport);

        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("application/json");
            softly.assertThat(request.headers().get(IF_NONE_MATCH)).contains("\"abc\"");
            softly.assertThat(new String(
                            request.body().mapBytes(bytes -> bytes, () -> new byte[0]), StandardCharsets.UTF_8))
                    .contains("\"text\"");
        });
    }

    @Test
    void post_shouldSendQueryRawBodyAndOverlay_whenHttpBodyQueryAndHeadersGiven() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final HttpBody body = HttpBody.of("zip".getBytes(StandardCharsets.UTF_8));
        final HttpHeaders overlay = HttpHeaders.of(CONTENT_TYPE, "application/zip");

        caller.send(api.post(RELEASE_ASSETS)
                .withQuery(Map.of("name", "example.zip"))
                .withBody(body)
                .withHeaders(overlay));

        final HttpRequest request = singleRequest(transport);

        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(request.uri())
                    .hasToString("https://api.github.com/repos/octocat/Hello-World/releases/1/assets?name=example.zip");
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("application/zip");
            softly.assertThat(request.body().mapBytes(bytes -> bytes, () -> new byte[0]))
                    .isEqualTo("zip".getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void post_shouldThrow_whenHttpBodyNull() {
        final Throwable thrown = catchThrowable(() -> api.post(MARKDOWN_RAW).withBody(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("body");
    }

    @Test
    void post_shouldThrow_whenOverlayHeadersNullForHttpBody() {
        final Throwable thrown = catchThrowable(
                () -> api.post(MARKDOWN_RAW).withBody(HttpBody.of(new byte[0])).withHeaders(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void post_shouldThrow_whenOverlayHeadersNullForObjectBody() {
        final Throwable thrown = catchThrowable(
                () -> caller.json(api.post(MARKDOWN), Map.of("text", "Hi")).withHeaders(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void redirectLocation_shouldReturnLocation_whenResponseIsFound() throws Exception {
        when(transport.execute(any()))
                .thenReturn(HttpResponses.of(
                        HttpStatus.FOUND.code(),
                        Map.of(LOCATION, List.of("https://codeload.github.com/octocat/Hello-World/tar.gz/main")),
                        HttpBody.absent()));

        final URI location = caller.redirectLocation(api.get(REPOS_OCTOCAT_TARBALL));

        assertThat(location).hasToString("https://codeload.github.com/octocat/Hello-World/tar.gz/main");
    }

    @Test
    void redirectLocation_shouldSendVersionHeader_whenCalled() throws Exception {
        when(transport.execute(any()))
                .thenReturn(HttpResponses.of(
                        HttpStatus.FOUND.code(),
                        Map.of(LOCATION, List.of("https://codeload.github.com/octocat/Hello-World/tar.gz/main")),
                        HttpBody.absent()));

        caller.redirectLocation(api.get(REPOS_OCTOCAT_TARBALL));

        assertVersionHeader(singleRequest(transport));
    }

    @Test
    void postUri_shouldSendVersionAndContentType_whenUploadingAsset() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(api.post(UPLOAD_URI)
                .withBody(HttpBody.of(new byte[] {1, 2}))
                .withHeader(CONTENT_TYPE, "application/octet-stream"));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.uri()).isEqualTo(UPLOAD_URI);
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("application/octet-stream");
            softly.assertThat(request.headers().get(API_VERSION)).contains(GitHubClient.API_VERSION);
        });
    }

    @ParameterizedTest
    @MethodSource("emptySuccessBodies")
    void fetchTextOrEmpty_shouldReturnEmptyText_whenSuccessBodyIsEmpty(final HttpBody body) throws Exception {
        when(transport.execute(any())).thenReturn(HttpResponses.of(HttpStatus.OK.code(), Map.of(), body));

        final Optional<String> text = api.fetchTextOrEmpty(api.get(REPOS_ONE), "application/vnd.github.diff");

        assertThat(text).contains("");
    }

    @Test
    void fetchTextOrEmpty_shouldDecodeWithCharset_whenContentTypeNamesCharset() throws Exception {
        when(transport.execute(any()))
                .thenReturn(HttpResponses.of(
                        HttpStatus.OK.code(),
                        Map.of(CONTENT_TYPE, List.of("text/plain; charset=ISO-8859-1")),
                        HttpBody.of("café".getBytes(StandardCharsets.ISO_8859_1))));

        final Optional<String> text = api.fetchTextOrEmpty(api.get(REPOS_ONE), "application/vnd.github.diff");

        assertThat(text).contains("café");
    }

    @Test
    void fetchTextOrEmpty_shouldReturnEmpty_whenResponseIsNotFound() throws Exception {
        when(transport.execute(any()))
                .thenReturn(HttpResponses.of(HttpStatus.NOT_FOUND.code(), Map.of(), HttpBody.absent()));

        final Optional<String> text = api.fetchTextOrEmpty(api.get(REPOS_ONE), "application/vnd.github.diff");

        assertThat(text).isEmpty();
    }

    private static Stream<HttpBody> emptySuccessBodies() {
        return Stream.of(HttpBody.of(new byte[0]), HttpBody.absent());
    }

    private static void assertVersionHeader(final HttpRequest request) {
        assertThat(request.headers().get(API_VERSION)).contains(GitHubClient.API_VERSION);
    }

    private static Stream<VersionHeaderCall> absentBodyCalls() {
        return Stream.of(
                (api, caller) -> caller.send(api.put(REPOS_ONE)),
                (api, caller) -> caller.send(api.put(REPOS_ONE).withQuery(Map.of("name", "demo"))),
                (api, caller) -> caller.send(api.delete(USER)),
                (api, caller) -> caller.send(api.delete(USER).withQuery(Map.of("state", "open"))));
    }

    private static Stream<VersionHeaderCall> versionHeaderCalls() {
        final Object body = Map.of("name", "demo");
        return Stream.of(
                (api, caller) -> caller.send(api.get(USER)),
                (api, caller) -> caller.send(api.get(USER).withQuery(Map.of("state", "open"))),
                (api, caller) -> caller.send(caller.json(api.post(REPOS), body)),
                (api, caller) -> caller.send(caller.json(api.post(REPOS), body).withQuery(Map.of("name", "demo"))),
                (api, caller) -> caller.send(api.put(REPOS_ONE)),
                (api, caller) -> caller.send(api.put(REPOS_ONE).withQuery(Map.of("name", "demo"))),
                (api, caller) -> caller.send(caller.json(api.put(REPOS_ONE), body)),
                (api, caller) ->
                        caller.send(caller.json(api.put(REPOS_ONE), body).withQuery(Map.of("name", "demo"))),
                (api, caller) -> caller.send(caller.json(api.patch(REPOS_ONE), body)),
                (api, caller) ->
                        caller.send(caller.json(api.patch(REPOS_ONE), body).withQuery(Map.of("name", "demo"))),
                (api, caller) -> caller.send(api.delete(USER)),
                (api, caller) -> caller.send(api.delete(USER).withQuery(Map.of("state", "open"))),
                (api, caller) -> caller.send(caller.json(api.delete(USER_EMAILS), body)));
    }

    @FunctionalInterface
    interface VersionHeaderCall {
        void execute(TestApi api, GitHubCaller caller) throws Exception;
    }

    @Test
    void requireFlavor_shouldAllow_whenNoFlavorIsConfigured() {
        final Throwable thrown = catchThrowable(() -> api.checkFlavor(GitHubFlavor.GHES));

        assertThat(thrown).isNull();
    }

    @Test
    void requireFlavor_shouldReject_whenTheConfiguredFlavorIsNotAvailable() {
        final TestApi flavored =
                new TestApi(GitHubCaller.of(TestGitHubClients.flavored(transport, GitHubFlavor.CLOUD)));

        final Throwable thrown = catchThrowable(() -> flavored.checkFlavor(GitHubFlavor.GHEC));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("CLOUD");
    }

    @Test
    void listValues_shouldReturnTheFirstPage_whenTheResponseIsAList() throws Exception {
        when(transport.execute(any()))
                .thenReturn(HttpResponses.of(
                        HttpStatus.OK.code(), Map.of(), HttpBody.of("[\"a\",\"b\"]".getBytes(StandardCharsets.UTF_8))));

        final GitHubPage<String> page = api.values(
                REPOS,
                Map.of(),
                GitHubPageNumberRequest.of(1, 10),
                GitHubListSpec.pages(JsonTypes.listOf(String.class)));

        assertThat(page.items()).containsExactly("a", "b");
    }
}
