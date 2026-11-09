package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.API_VERSION;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.MARKDOWN;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.MARKDOWN_RAW;
import static io.github.piotr_janczukowicz.github.client.GitHubCallerFixture.REPOS;
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
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.test.TestClients.GITHUB_BASE;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.ok;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubForbiddenException;
import io.github.piotr_janczukowicz.github.client.pagination.GitHubListSpec;
import io.github.piotr_janczukowicz.github.json.GitHubJsonCodecs;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageNumberRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKindValidators;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.test.model.TestUser;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

@UnitTest
class GitHubCallerTest {

    private static final PathTemplate REPOS_COLLABORATOR = PathTemplate.of("/repos/o/r/collaborators/u");
    private static final PathTemplate USERS_OCTOCAT = PathTemplate.of("/users/octocat");
    private static final PathTemplate USERS_MISSING = PathTemplate.of("/users/missing");
    private static final PathTemplate INTERACTION_LIMITS = PathTemplate.of("/interaction-limits");
    private static final PathTemplate COMMITS = PathTemplate.of("/commits");
    private static final PathTemplate MERGES = PathTemplate.of("/merges");
    private static final PathTemplate MOVED = PathTemplate.of("/moved");
    private static final PathTemplate SEARCH = PathTemplate.of("/search");

    private final GitHubCallerFixture.Wiring wiring = GitHubCallerFixture.mocked();
    private final HttpTransport transport = wiring.transport();
    private final GitHubCaller caller = wiring.caller();
    private final TestApi api = wiring.api();

    @Test
    void requireFlavor_shouldAllowEveryFlavor_whenNoFlavorConfigured() {
        final Throwable thrown = catchThrowable(() -> caller.requireFlavor(GitHubFlavor.GHEC));

        assertThat(thrown).isNull();
    }

    @Test
    void requireFlavor_shouldAllow_whenConfiguredFlavorIsAvailable() {
        final GitHubCaller flavored = flavoredCaller(GitHubFlavor.GHEC);

        final Throwable thrown = catchThrowable(() -> flavored.requireFlavor(GitHubFlavor.CLOUD, GitHubFlavor.GHEC));

        assertThat(thrown).isNull();
    }

    @Test
    void requireFlavor_shouldThrow_whenConfiguredFlavorIsNotAvailable() {
        final GitHubCaller flavored = flavoredCaller(GitHubFlavor.CLOUD);

        final Throwable thrown = catchThrowable(() -> flavored.requireFlavor(GitHubFlavor.GHEC));

        assertThat(thrown)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CLOUD")
                .hasMessageContaining("[GHEC]");
    }

    private GitHubCaller flavoredCaller(final GitHubFlavor flavor) {
        return GitHubCaller.of(TestGitHubClients.flavored(transport, flavor));
    }

    @Test
    void requireFlavor_shouldAllow_whenGhesClientAsksForGhes() {
        final GitHubCaller ghes = ghesCaller(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(() -> ghes.requireFlavor(GitHubFlavor.GHEC, GitHubFlavor.GHES));

        assertThat(thrown).isNull();
    }

    @Test
    void requireFlavor_shouldThrowAndNameGhes_whenGhesClientAsksForGhecOnly() {
        final GitHubCaller ghes = ghesCaller(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(() -> ghes.requireFlavor(GitHubFlavor.GHEC));

        assertThat(thrown)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("GHES")
                .hasMessageContaining("[GHEC]");
    }

    @Test
    void requireGhesVersion_shouldAllowEveryRange_whenClientIsNotGhes() {
        final GitHubCaller cloud = flavoredCaller(GitHubFlavor.CLOUD);

        final Throwable thrown = catchThrowable(() -> cloud.requireGhesVersion("3.22", ""));

        assertThat(thrown).isNull();
    }

    @Test
    void requireGhesVersion_shouldAllowEveryRange_whenClientHasNoFlavor() {
        final Throwable thrown = catchThrowable(() -> caller.requireGhesVersion("3.22", ""));

        assertThat(thrown).isNull();
    }

    @ParameterizedTest
    @CsvSource(
            value = {"3.21|3.21|", "3.21||3.21", "3.21|3.21|3.22", "3.21||", "3.22|3.21|", "3.22||3.22"},
            delimiter = '|')
    void requireGhesVersion_shouldAllow_whenVersionIsInsideTheRange(
            final String version, final String since, final String until) {
        final GitHubCaller ghes = ghesCaller(GhesVersion.parse(version));
        final String first = since == null ? "" : since;
        final String last = until == null ? "" : until;

        final Throwable thrown = catchThrowable(() -> ghes.requireGhesVersion(first, last));

        assertThat(thrown).isNull();
    }

    @Test
    void requireGhesVersion_shouldThrowAndNameTheRange_whenVersionIsBeforeSince() {
        final GitHubCaller ghes = ghesCaller(new GhesVersion(3, 21));

        final Throwable thrown = catchThrowable(() -> ghes.requireGhesVersion("3.22", ""));

        assertThat(thrown)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Enterprise Server 3.21")
                .hasMessageContaining("exists from 3.22");
    }

    @Test
    void requireGhesVersion_shouldThrowAndNameTheRange_whenVersionIsAfterUntil() {
        final GitHubCaller ghes = ghesCaller(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(() -> ghes.requireGhesVersion("", "3.21"));

        assertThat(thrown)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Enterprise Server 3.22")
                .hasMessageContaining("exists up to 3.21");
    }

    @Test
    void requireGhesVersion_shouldNameBothEnds_whenVersionIsOutsideAClosedRange() {
        final GitHubCaller ghes = ghesCaller(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(() -> ghes.requireGhesVersion("3.20", "3.21"));

        assertThat(thrown)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("from 3.20 up to 3.21");
    }

    @Test
    void requireGhesVersion_shouldThrowIllegalArgument_whenRangeIsMalformed() {
        final GitHubCaller ghes = ghesCaller(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(() -> ghes.requireGhesVersion("latest", ""));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireGhesVersion_shouldDelegateToTheCaller_whenCalledByAResourceApi() {
        final TestApi ghesApi = new TestApi(ghesCaller(new GhesVersion(3, 21)));

        final Throwable thrown = catchThrowable(() -> ghesApi.checkGhesVersion("3.22", ""));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("from 3.22");
    }

    @Test
    void requireGhesVersion_shouldAllow_whenResourceApiVersionIsInsideTheRange() {
        final TestApi ghesApi = new TestApi(ghesCaller(new GhesVersion(3, 22)));

        final Throwable thrown = catchThrowable(() -> ghesApi.checkGhesVersion("3.22", ""));

        assertThat(thrown).isNull();
    }

    private GitHubCaller ghesCaller(final GhesVersion version) {
        return GitHubCaller.of(TestGitHubClients.ghes(transport, version));
    }

    @Test
    void get_shouldSendGetWithoutJsonContentType_whenNoBody() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(api.get(USER));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.GET);
            softly.assertThat(request.uri().toString()).endsWith("/user");
            softly.assertThat(request.headers().get(CONTENT_TYPE)).isEmpty();
            softly.assertThat(request.headers().get(API_VERSION)).contains(GitHubClient.API_VERSION);
        });
    }

    @Test
    void get_shouldExpandTemplate_whenPlaceholdersProvided() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final PathTemplate path = PathTemplate.of("/repos/{owner}/{repo}");

        caller.send(api.get(path, "o", "r"));

        assertThat(singleRequest(transport).uri().getPath()).isEqualTo("/repos/o/r");
    }

    @Test
    void send_shouldUseAbsoluteApiUri_whenCallTargetsAbsoluteUri() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final URI uri = URI.create("https://api.github.com/user");

        caller.send(HttpCall.get(uri));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.GET);
            softly.assertThat(request.uri()).isEqualTo(uri);
        });
    }

    @Test
    void post_shouldExpandTemplate_whenNoPlaceholders() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final PathTemplate path = PathTemplate.of("/markdown");

        caller.send(api.post(path));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(request.uri().getPath()).isEqualTo("/markdown");
        });
    }

    @Test
    void put_shouldExpandTemplate_whenPlaceholdersProvided() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final PathTemplate path = PathTemplate.of("/user/starred/{owner}/{repo}");

        caller.send(api.put(path, "o", "r"));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.PUT);
            softly.assertThat(request.uri().getPath()).isEqualTo("/user/starred/o/r");
        });
    }

    @Test
    void patch_shouldExpandTemplate_whenPlaceholdersProvided() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final PathTemplate path = PathTemplate.of("/repos/{owner}/{repo}");

        caller.send(api.patch(path, "o", "r"));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.PATCH);
            softly.assertThat(request.uri().getPath()).isEqualTo("/repos/o/r");
        });
    }

    @Test
    void delete_shouldExpandTemplate_whenPlaceholdersProvided() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final PathTemplate path = PathTemplate.of("/repos/{owner}/{repo}");

        caller.send(api.delete(path, "o", "r"));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
            softly.assertThat(request.uri().getPath()).isEqualTo("/repos/o/r");
        });
    }

    @Test
    void post_shouldEncodeJsonAndSetContentType_whenJsonCalled() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(caller.json(api.post(REPOS), Map.of("name", "demo")));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("application/json");
            softly.assertThat(request.body().mapBytes(bytes -> new String(bytes, StandardCharsets.UTF_8), () -> ""))
                    .contains("demo");
        });
    }

    @Test
    void jsonIfPresent_shouldOmitBodyAndContentType_whenValueNull() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(caller.jsonIfPresent(api.patch(REPOS_ONE), null));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.method()).isEqualTo(HttpMethod.PATCH);
            softly.assertThat(request.body().isAbsent()).isTrue();
            softly.assertThat(request.headers().get(CONTENT_TYPE)).isEmpty();
        });
    }

    @Test
    void jsonIfPresent_shouldEncodeJson_whenValuePresent() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(caller.jsonIfPresent(api.put(REPOS_ONE), Map.of("name", "demo")));

        assertThat(singleRequest(transport).headers().get(CONTENT_TYPE)).contains("application/json");
    }

    @Test
    void body_shouldSendRawBytesWithoutJsonContentType_whenCalled() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());
        final HttpBody body = HttpBody.of("raw".getBytes(StandardCharsets.UTF_8));

        caller.send(api.post(MARKDOWN_RAW).withBody(body).withHeader(CONTENT_TYPE, "text/plain"));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.body().mapBytes(bytes -> new String(bytes, StandardCharsets.UTF_8), () -> ""))
                    .isEqualTo("raw");
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("text/plain");
        });
    }

    @Test
    void headers_shouldMergeAfterJson_whenOverlayReplacesContentType() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(caller.json(api.post(MARKDOWN), Map.of("text", "hi"))
                .withHeaders(HttpHeaders.of(CONTENT_TYPE, "text/plain", ACCEPT, "text/html")));

        final HttpRequest request = singleRequest(transport);
        assertSoftly(softly -> {
            softly.assertThat(request.headers().get(CONTENT_TYPE)).contains("text/plain");
            softly.assertThat(request.headers().get(ACCEPT)).contains("text/html");
        });
    }

    @Test
    void query_shouldAppendSingleValuedParameters_whenCalled() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(api.get(USER).withQuery(Map.of("state", "open")));

        assertThat(singleRequest(transport).uri().toString()).contains("state=open");
    }

    @Test
    void queryValues_shouldAppendRepeatedParameters_whenCalled() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(api.get(SEARCH).withQueryValues(Map.of("q", List.of("a", "b"))));

        assertThat(singleRequest(transport).uri().toString()).contains("q=a").contains("q=b");
    }

    @Test
    void search_shouldSendRepeatedValues_whenQueryValuesRepeat() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                        {"total_count":1,"incomplete_results":false,"items":[{"name":"ruby"}]}
                        """));

        caller.search(
                api.get(SEARCH_TOPICS).withQueryValues(Map.of("labels", List.of("a", "b"), "empty", List.of())),
                GitHubPageNumberRequest.of(1, 1),
                JsonTypes.of(JsonNode.class),
                TOPIC_ENVELOPE);

        assertThat(singleRequest(transport).uri().toString())
                .contains("labels=a")
                .contains("labels=b")
                .doesNotContain("empty=");
    }

    @Test
    void pageParameters_shouldBindPageAndPerPage_whenPageRequest() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(caller.pageParameters(
                api.get(COMMITS).withQuery(Map.of("sha", "main")),
                GitHubPageNumberRequest.of(2, 10),
                GitHubPageRequestKindValidators.PAGE));

        final String uri = singleRequest(transport).uri().toString();
        assertThat(uri).contains("sha=main").contains("page=2").contains("per_page=10");
    }

    @Test
    void pageParameters_shouldThrow_whenKindNotAllowed() {
        final Throwable thrown = catchThrowable(() -> caller.pageParameters(
                api.get(COMMITS), GitHubPageRequest.byCursor(), GitHubPageRequestKindValidators.PAGE));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    @Test
    void fetch_shouldDecodeClass_whenOk() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("public-user-octocat.json")));

        final TestUser user = caller.fetch(api.get(USERS_OCTOCAT), TestUser.class);

        assertThat(user).isEqualTo(TestUser.OCTOCAT);
    }

    @Test
    void fetch_shouldDecodeTypeReference_whenOk() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("users-octocat.json")));

        final List<TestUser> users = caller.fetch(api.get(USERS), JsonTypes.listOf(TestUser.class));

        assertThat(users).containsExactly(TestUser.OCTOCAT);
    }

    @Test
    void fetchOrEmpty_shouldReturnEmpty_whenNotFound() throws Exception {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_FOUND.code(), "{\"message\":\"Not Found\"}"));
        final JsonCodec codec = spy(GitHubJsonCodecs.defaults());

        final Optional<TestUser> user;
        try (final GitHubClient client = TestGitHubClients.raw(
                transport, TestClients.configurationBuilder(GITHUB_BASE).build(), TestClients.auth())) {
            final GitHubCaller spied = new GitHubCaller(client, codec);
            user = spied.fetchOrEmpty(new TestApi(spied).get(USERS_MISSING), TestUser.class);
        }

        assertThat(user).isEmpty();
        verify(codec, never()).decode(any(byte[].class), eq(JsonNode.class));
    }

    @Test
    void fetchOrEmpty_shouldThrow_whenForbidden() throws Exception {
        when(transport.execute(any())).thenReturn(response(HttpStatus.FORBIDDEN.code(), "{\"message\":\"nope\"}"));

        final Throwable thrown = catchThrowable(() -> caller.fetchOrEmpty(api.get(USERS_MISSING), TestUser.class));

        assertThat(thrown).isInstanceOf(GitHubForbiddenException.class);
    }

    @Test
    void fetchUnless_shouldReturnEmpty_whenStatusMatches() throws Exception {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        final Optional<TestUser> user = caller.fetchUnless(api.get(MERGES), TestUser.class, HttpStatus.NO_CONTENT);

        assertThat(user).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", " { \n\t} ", "{\r\n}", "{\f}", "{\u000B}"})
    void fetchUnless_shouldReturnEmpty_whenBodyIsEmptyJsonObject(final String body) throws Exception {
        when(transport.execute(any())).thenReturn(ok(body));

        final Optional<TestUser> user =
                caller.fetchUnless(api.get(INTERACTION_LIMITS), TestUser.class, HttpStatus.NO_CONTENT);

        assertThat(user).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1}", "[]", "{ }x", "{{}}", "null", "   ", "{"})
    void fetchUnless_shouldNotTreatBodyAsEmpty_whenJsonIsNotAnEmptyObject(final String body) throws Exception {
        when(transport.execute(any())).thenReturn(ok(body));

        Optional<TestUser> user = Optional.empty();
        Throwable thrown = null;
        try {
            user = caller.fetchUnless(api.get(INTERACTION_LIMITS), TestUser.class, HttpStatus.NO_CONTENT);
        } catch (final RuntimeException ex) {
            thrown = ex;
        }

        assertThat(thrown != null || user.isPresent()).isTrue();
    }

    @Test
    void fetchUnless_shouldReturnEmpty_whenStatusMatchesRegardlessOfBody() throws Exception {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), "{\"a\":1}"));

        final Optional<TestUser> user = caller.fetchUnless(api.get(MERGES), TestUser.class, HttpStatus.NO_CONTENT);

        assertThat(user).isEmpty();
    }

    @Test
    void fetchUnless_shouldReturnEmpty_whenTypeReferenceBodyIsEmptyJsonObject() throws Exception {
        when(transport.execute(any())).thenReturn(ok("{}"));

        final Optional<List<TestUser>> users = caller.fetchUnless(
                api.get(INTERACTION_LIMITS), JsonTypes.listOf(TestUser.class), HttpStatus.NO_CONTENT);

        assertThat(users).isEmpty();
    }

    @Test
    void fetchUnless_shouldReturnEmpty_whenBodyIsEmptyJsonObjectWithInnerWhitespace() throws Exception {
        when(transport.execute(any())).thenReturn(ok("{ }"));

        final Optional<TestUser> user =
                caller.fetchUnless(api.get(INTERACTION_LIMITS), TestUser.class, HttpStatus.NO_CONTENT);

        assertThat(user).isEmpty();
    }

    @Test
    void fetchUnless_shouldDecodeTypeReference_whenBodyPresent() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("users-octocat.json")));

        final Optional<List<TestUser>> users = caller.fetchUnless(
                api.get(USERS), JsonTypes.listOf(TestUser.class), HttpStatus.ACCEPTED, HttpStatus.NO_CONTENT);

        assertThat(users).contains(List.of(TestUser.OCTOCAT));
    }

    @Test
    void isExisting_shouldReturnFalse_whenNotFound() throws Exception {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NOT_FOUND.code(), "{\"message\":\"Not Found\"}"));
        final JsonCodec codec = spy(GitHubJsonCodecs.defaults());

        final boolean exists;
        try (final GitHubClient client = TestGitHubClients.raw(
                transport, TestClients.configurationBuilder(GITHUB_BASE).build(), TestClients.auth())) {
            final GitHubCaller spied = new GitHubCaller(client, codec);
            exists = spied.isExisting(new TestApi(spied).get(REPOS_COLLABORATOR));
        }

        assertThat(exists).isFalse();
        verify(codec, never()).decode(any(byte[].class), eq(JsonNode.class));
    }

    @Test
    void isExisting_shouldReturnTrue_whenNoContent() throws Exception {
        when(transport.execute(any())).thenReturn(response(HttpStatus.NO_CONTENT.code(), null));

        assertThat(caller.isExisting(api.get(REPOS_COLLABORATOR))).isTrue();
    }

    @Test
    void isExisting_shouldNotFollowRedirects_whenFound() throws Exception {
        when(transport.execute(any()))
                .thenReturn(HttpResponses.of(
                        HttpStatus.FOUND.code(),
                        Map.of(LOCATION, List.of("https://api.github.com/elsewhere")),
                        HttpBody.absent()));

        final Throwable thrown = catchThrowable(() -> caller.isExisting(api.get(MOVED)));

        verify(transport, times(1)).execute(any());
        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void isExisting_shouldLeaveRedirectFollowingOn_whenCallIsReused() throws Exception {
        when(transport.execute(any()))
                .thenReturn(response(HttpStatus.NO_CONTENT.code(), null))
                .thenReturn(HttpResponses.of(
                        HttpStatus.FOUND.code(),
                        Map.of(LOCATION, List.of("https://api.github.com/elsewhere")),
                        HttpBody.absent()))
                .thenReturn(response(HttpStatus.OK.code(), "{\"ok\":true}"));

        final HttpCall call = api.get(MOVED);
        caller.isExisting(call);
        final JsonNode node = caller.fetch(call, JsonNode.class);

        verify(transport, times(3)).execute(any());
        assertThat(node.get("ok").asBoolean()).isTrue();
    }

    @Test
    void isExisting_shouldThrow_whenServerError() throws Exception {
        when(transport.execute(any())).thenReturn(response(HttpStatus.INTERNAL_SERVER_ERROR.code(), "oops"));

        final Throwable thrown = catchThrowable(() -> caller.isExisting(api.get(REPOS_COLLABORATOR)));

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void list_shouldThrow_whenKindNotAllowed() {
        final Throwable thrown = catchThrowable(() -> caller.list(
                api.get(USERS), GitHubPageRequest.byCursor(), GitHubListSpec.pages(JsonTypes.listOf(Object.class))));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    @Test
    void list_shouldThrow_whenCallIsNotGet() {
        final Throwable thrown = catchThrowable(() -> caller.list(
                api.post(USERS), GitHubPageRequest.byPage(), GitHubListSpec.pages(JsonTypes.listOf(Object.class))));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GET");
    }

    @Test
    void list_shouldThrow_whenCallHasBody() {
        final HttpCall call = api.get(USERS).withBody(HttpBody.of(new byte[] {1}));

        final Throwable thrown = catchThrowable(() ->
                caller.list(call, GitHubPageRequest.byPage(), GitHubListSpec.pages(JsonTypes.listOf(Object.class))));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("body");
    }

    @Test
    void search_shouldThrow_whenCallIsNotGet() {
        final Throwable thrown = catchThrowable(() -> caller.search(
                api.post(USERS), GitHubPageRequest.byPage(), JsonTypes.of(JsonNode.class), TOPIC_ENVELOPE));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GET");
    }

    @Test
    void search_shouldThrow_whenCallHasNoPath() {
        final Throwable thrown = catchThrowable(() -> caller.search(
                api.post(UPLOAD_URI), GitHubPageRequest.byPage(), JsonTypes.of(JsonNode.class), TOPIC_ENVELOPE));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void pageParameters_shouldThrow_whenKindsNull() {
        final Throwable thrown =
                catchThrowable(() -> caller.pageParameters(api.get(COMMITS), GitHubPageRequest.byPage(), null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void list_shouldThrowPath_whenNoPathAndKindNotAllowed() {
        final Throwable thrown = catchThrowable(() -> caller.list(
                api.post(UPLOAD_URI),
                GitHubPageRequest.byCursor(),
                GitHubListSpec.pages(JsonTypes.listOf(Object.class))));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void list_shouldThrow_whenUploadUriHasNoPath() {
        final Throwable thrown = catchThrowable(() -> caller.list(
                api.post(UPLOAD_URI),
                GitHubPageRequest.byPage(),
                GitHubListSpec.pages(JsonTypes.listOf(Object.class))));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void list_shouldThrow_whenAbsoluteApiUriHasNoPath() {
        final URI apiUri = URI.create("https://api.github.com/user");

        final Throwable thrown = catchThrowable(() -> caller.list(
                HttpCall.get(apiUri),
                GitHubPageRequest.byPage(),
                GitHubListSpec.pages(JsonTypes.listOf(Object.class))));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void search_shouldDecodeEnvelope_whenInvoked() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                {"total_count":1,"incomplete_results":false,"items":[{"name":"ruby"}]}
                """));

        final GitHubPage<String> result = caller.search(
                api.get(SEARCH_TOPICS)
                        .withQuery(Map.of("q", "ruby"))
                        .withHeader(ACCEPT, "application/vnd.github.text-match+json"),
                GitHubPageNumberRequest.of(1, 1),
                JsonTypes.of(JsonNode.class),
                TOPIC_ENVELOPE);

        assertThat(result.items()).isNotEmpty();
        assertThat(singleRequest(transport).headers().get(ACCEPT)).contains("application/vnd.github.text-match+json");
    }

    @Test
    void query_shouldThrow_whenMapNull() {
        final Throwable thrown = catchThrowable(() -> api.get(USER).withQuery(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("query");
    }

    @Test
    void json_shouldThrow_whenBodyNull() {
        final Throwable thrown = catchThrowable(() -> caller.json(api.post(REPOS), null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    void fetchUnless_shouldDecodeClass_whenBodyPresentAndStatusNotListed() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("public-user-octocat.json")));

        final Optional<TestUser> user =
                caller.fetchUnless(api.get(USERS_OCTOCAT), TestUser.class, HttpStatus.NO_CONTENT);

        assertThat(user).contains(TestUser.OCTOCAT);
    }

    @Test
    void fetchOrEmpty_shouldReturnValue_whenOk() throws Exception {
        when(transport.execute(any())).thenReturn(ok(json("public-user-octocat.json")));

        final Optional<TestUser> user = caller.fetchOrEmpty(api.get(USERS_OCTOCAT), TestUser.class);

        assertThat(user).contains(TestUser.OCTOCAT);
    }

    @Test
    void queryValues_shouldSkipEmptyValueLists_whenBuildingPageQuery() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        caller.send(caller.pageParameters(
                api.get(COMMITS).withQueryValues(Map.of("empty", List.of(), "sha", List.of("main"))),
                GitHubPageNumberRequest.of(1, 10),
                GitHubPageRequestKindValidators.PAGE));

        assertThat(singleRequest(transport).uri().toString())
                .contains("sha=main")
                .doesNotContain("empty=");
    }

    @Test
    void fetchUnless_shouldThrow_whenStatusesNull() {
        final Throwable thrown =
                catchThrowable(() -> caller.fetchUnless(api.get(USER), TestUser.class, (HttpStatus[]) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("statuses");
    }

    @Test
    void fetchUnless_shouldThrow_whenStatusElementNull() {
        final Throwable thrown =
                catchThrowable(() -> caller.fetchUnless(api.get(USER), TestUser.class, HttpStatus.NO_CONTENT, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("status");
    }

    @Test
    void search_shouldThrow_whenEnvelopeNull() {
        final Throwable thrown = catchThrowable(() ->
                caller.search(api.get(SEARCH_TOPICS), GitHubPageRequest.byPage(), JsonTypes.of(JsonNode.class), null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("envelope");
    }

    @Test
    void fetchUnless_shouldReturnEmpty_whenBodyAbsentAndStatusNotListed() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        final Optional<TestUser> user = caller.fetchUnless(api.get(USER), TestUser.class, HttpStatus.NO_CONTENT);

        assertThat(user).isEmpty();
    }

    @Test
    void fetchUnless_shouldReturnEmpty_whenTypeReferenceBodyAbsentAndStatusNotListed() throws Exception {
        when(transport.execute(any())).thenReturn(okResponse());

        final Optional<List<TestUser>> users =
                caller.fetchUnless(api.get(USER), JsonTypes.listOf(TestUser.class), HttpStatus.NO_CONTENT);

        assertThat(users).isEmpty();
    }

    @Test
    void fetchUnless_shouldThrow_whenTypeNull() {
        final Throwable thrown =
                catchThrowable(() -> caller.fetchUnless(api.get(USER), (Class<TestUser>) null, HttpStatus.NO_CONTENT));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("type");
    }

    @Test
    void fetchUnless_shouldThrow_whenTypeReferenceNull() {
        final Throwable thrown = catchThrowable(
                () -> caller.fetchUnless(api.get(USER), (JsonType<List<TestUser>>) null, HttpStatus.NO_CONTENT));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("type");
    }

    @Test
    void body_shouldThrow_whenBodyNull() {
        final Throwable thrown = catchThrowable(() -> api.post(MARKDOWN_RAW).withBody(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("body");
    }

    @Test
    void headers_shouldThrow_whenHeadersNull() {
        final Throwable thrown = catchThrowable(() -> api.get(USER).withHeaders(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");
    }

    @Test
    void list_shouldThrow_whenSpecNull() {
        final Throwable thrown = catchThrowable(() -> caller.list(api.get(USERS), GitHubPageRequest.byPage(), null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("spec");
    }

    @Test
    void search_shouldThrow_whenKindNotAllowed() {
        final Throwable thrown = catchThrowable(() -> caller.search(
                api.get(SEARCH_TOPICS), GitHubPageRequest.byCursor(), JsonTypes.of(JsonNode.class), TOPIC_ENVELOPE));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    @Test
    void execute_shouldThrow_whenCallNull() {
        try (final GitHubClient client = TestClients.client(transport)) {
            final Throwable thrown = catchThrowable(() -> client.execute(null));

            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("call");
        }
    }
}
