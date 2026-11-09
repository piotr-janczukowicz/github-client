package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.API_VERSION;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.get;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.post;
import static io.github.piotr_janczukowicz.github.client.GitHubClientSupport.send;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.USER_AGENT;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpMethod.POST;
import static io.github.piotr_janczukowicz.http.HttpStatus.FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.MOVED_PERMANENTLY;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.http.HttpStatus.TEMPORARY_REDIRECT;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@IntegrationTest
class GitHubClientRedirectIntegrationTest {

    @RegisterExtension
    static final StubHttpServer API = new StubHttpServer("127.0.0.1");

    @RegisterExtension
    static final StubHttpServer OTHER = new StubHttpServer("localhost");

    @Test
    void get_shouldDropAuthorization_whenRedirectIsCrossOrigin() throws Exception {
        OTHER.stub(GET, "/target", status(OK).body(new byte[] {9}));
        API.stubRedirect("/start", OTHER.uri("/target").toString(), MOVED_PERMANENTLY.code());

        try (final GitHubClient client = client(API)) {
            get(client, "/start");
        }

        API.verifyHeader(GET, "/start", AUTHORIZATION, "Bearer gh-token");
        OTHER.verifyRequested(GET, "/target");
        OTHER.verifyNoHeader(GET, "/target", AUTHORIZATION);
        OTHER.verifyHeader(GET, "/target", ACCEPT, GitHubClient.DEFAULT_ACCEPT);
        OTHER.verifyHeader(GET, "/target", USER_AGENT, TestClients.USER_AGENT);
        OTHER.verifyNoHeader(GET, "/target", API_VERSION);
    }

    @Test
    void get_shouldKeepAuthorization_whenSameOriginRedirect() throws Exception {
        API.stubRedirect("/start", API.uri("/target").toString(), MOVED_PERMANENTLY.code());
        API.stub(GET, "/target", status(OK).body(new byte[] {1}));

        try (final GitHubClient client = client(API)) {
            get(client, "/start");
        }

        API.verifyHeader(GET, "/start", AUTHORIZATION, "Bearer gh-token");
        API.verifyHeader(GET, "/target", AUTHORIZATION, "Bearer gh-token");
    }

    @Test
    void get_shouldDropExtraHeaders_whenRedirectIsCrossOrigin() throws Exception {
        OTHER.stub(GET, "/target", status(OK).body(new byte[] {9}));
        API.stubRedirect("/start", OTHER.uri("/target").toString(), MOVED_PERMANENTLY.code());
        final HttpClientConfiguration config = TestClients.configurationBuilder(URI.create(API.baseUri() + "/"))
                .defaultHeaders(HttpHeaders.of(Map.of("X-Custom", "secret")))
                .build();

        try (final GitHubClient client = TestGitHubClients.raw(HttpTransports.okHttp(), config, TestClients.auth())) {
            get(client, "/start");
        }

        API.verifyHeader(GET, "/start", "X-Custom", "secret");
        OTHER.verifyRequested(GET, "/target");
        OTHER.verifyNoHeader(GET, "/target", "X-Custom");
        OTHER.verifyNoHeader(GET, "/target", AUTHORIZATION);
        OTHER.verifyHeader(GET, "/target", ACCEPT, GitHubClient.DEFAULT_ACCEPT);
        OTHER.verifyHeader(GET, "/target", USER_AGENT, TestClients.USER_AGENT);
        OTHER.verifyNoHeader(GET, "/target", API_VERSION);
    }

    @Test
    void get_shouldNotRestoreAuthorization_whenLaterHopReturnsToOriginalOrigin() throws Exception {
        OTHER.stubRedirect("/mid", API.uri("/end").toString(), MOVED_PERMANENTLY.code());
        API.stubRedirect("/start", OTHER.uri("/mid").toString(), MOVED_PERMANENTLY.code());
        API.stub(GET, "/end", status(OK).body(new byte[] {1}));

        try (final GitHubClient client = client(API)) {
            get(client, "/start");
        }

        OTHER.verifyNoHeader(GET, "/mid", AUTHORIZATION);
        API.verifyNoHeader(GET, "/end", AUTHORIZATION);
        API.verifyHeader(GET, "/end", ACCEPT, GitHubClient.DEFAULT_ACCEPT);
        API.verifyNoHeader(GET, "/end", API_VERSION);
    }

    @Test
    void post_shouldNotFollow_whenFoundRedirectWouldDowngradeToGet() {
        OTHER.stub(GET, "/target", status(OK).body(new byte[] {9}));
        OTHER.stub(POST, "/target", status(OK).body(new byte[] {9}));
        API.stub(
                POST,
                "/start",
                status(FOUND.code()).header(LOCATION, OTHER.uri("/target").toString()));

        final Throwable thrown;
        try (final GitHubClient client = client(API)) {
            final HttpCall call = HttpCall.of(POST, "/start").withBody(HttpBody.of(new byte[] {1}));

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("would be sent as GET");
        OTHER.verifyRequested(GET, "/target", 0);
        OTHER.verifyRequested(POST, "/target", 0);
    }

    @Test
    void post_shouldNotFollow_whenTemporaryRedirectIsCrossOrigin() {
        OTHER.stub(POST, "/target", status(OK).body(new byte[] {9}));
        API.stub(
                POST,
                "/start",
                status(TEMPORARY_REDIRECT.code())
                        .header(LOCATION, OTHER.uri("/target").toString()));

        final Throwable thrown;
        try (final GitHubClient client = client(API)) {
            final HttpCall call = HttpCall.of(POST, "/start").withBody(HttpBody.of(new byte[] {1}));

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown)
                .isInstanceOf(GitHubApiException.class)
                .hasMessage("cross-origin redirect not followed for POST");
        OTHER.verifyRequested(POST, "/target", 0);
    }

    @Test
    void post_shouldRepeatBody_whenTemporaryRedirect() throws Exception {
        API.stub(
                POST,
                "/start",
                status(TEMPORARY_REDIRECT.code())
                        .header(LOCATION, API.uri("/target").toString()));
        API.stub(POST, "/target", status(OK).body(new byte[] {2}));

        try (final GitHubClient client = client(API)) {
            post(client, "/start", HttpBody.of(new byte[] {7, 8, 9}));
        }

        API.verifyBody(POST, "/start", new String(new byte[] {7, 8, 9}, StandardCharsets.UTF_8));
        API.verifyBody(POST, "/target", new String(new byte[] {7, 8, 9}, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource({"POST,301", "POST,302", "POST,303", "PATCH,303", "DELETE,303"})
    void write_shouldFailWithSafeLocation_whenSameOriginRedirectWouldDowngradeToGet(
            final HttpMethod method, final int status) {
        API.stub(
                method,
                "/start",
                status(status).header(LOCATION, API.uri("/target?token=signed").toString()));
        API.stub(GET, "/target", status(OK).body(new byte[] {9}));

        final Throwable thrown;
        try (final GitHubClient client = client(API)) {
            final HttpCall call = HttpCall.of(method, "/start");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        final GitHubApiException error = (GitHubApiException) thrown;
        assertThat(error.statusCode()).isEqualTo(status);
        assertThat(error.rawBody()).hasValueSatisfying(body -> assertThat(body).doesNotContain("token"));
        assertThat(error.getMessage()).doesNotContain("token");
        API.verifyRequested(GET, "/target", 0);
    }

    @ParameterizedTest
    @CsvSource({"PUT,301", "PATCH,301", "PATCH,302", "DELETE,301", "DELETE,302"})
    void write_shouldFollowWithMethodAndBody_whenSameOriginRepositoryMoved(final HttpMethod method, final int status)
            throws Exception {
        API.stub(
                method,
                "/start",
                status(status).header(LOCATION, API.uri("/renamed").toString()));
        API.stub(method, "/renamed", status(OK).body(new byte[] {2}));

        try (final GitHubClient client = client(API)) {
            send(client, method, "/start", HttpBody.of(new byte[] {7, 8}));
        }

        API.verifyBody(method, "/renamed", new String(new byte[] {7, 8}, StandardCharsets.UTF_8));
        API.verifyRequested(GET, "/renamed", 0);
    }

    @Test
    void get_shouldThrow_whenRedirectHopLimitExceeded() {
        API.stubRedirect("/h0", API.uri("/h1").toString(), MOVED_PERMANENTLY.code());
        API.stubRedirect("/h1", API.uri("/h2").toString(), MOVED_PERMANENTLY.code());
        API.stubRedirect("/h2", API.uri("/h3").toString(), MOVED_PERMANENTLY.code());
        API.stubRedirect("/h3", API.uri("/h4").toString(), MOVED_PERMANENTLY.code());
        API.stubRedirect("/h4", API.uri("/h5").toString(), MOVED_PERMANENTLY.code());
        API.stubRedirect("/h5", API.uri("/h6").toString(), MOVED_PERMANENTLY.code());

        final Throwable thrown;
        try (final GitHubClient client = client(API)) {
            final HttpCall call = HttpCall.get("/h0");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("hop limit");
    }

    private static GitHubClient client(final StubHttpServer server) {
        return TestClients.client(URI.create(server.baseUri() + "/"), HttpTransports.okHttp());
    }
}
