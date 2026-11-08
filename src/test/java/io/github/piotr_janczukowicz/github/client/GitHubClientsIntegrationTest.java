package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LOCATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.USER_AGENT;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpStatus.FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.NOT_FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.http.HttpStatus.TOO_MANY_REQUESTS;
import static io.github.piotr_janczukowicz.http.HttpStatus.UNAUTHORIZED;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubUnauthorizedException;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.ResponseTooLargeException;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpCallEvent;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

@IntegrationTest
@Isolated
class GitHubClientsIntegrationTest {

    private static final String USER_PATH = "/user";

    @RegisterExtension
    static final StubHttpServer API = new StubHttpServer("127.0.0.1");

    @RegisterExtension
    static final StubHttpServer OTHER = new StubHttpServer("localhost");

    @Test
    void create_shouldApplyTransportConfiguration_whenConfigurationGiven() {
        API.stub(GET, USER_PATH, status(OK).body(new byte[64]));
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.builder()
                .configuration(configuration())
                .transport(HttpTransportConfiguration.builder()
                        .maxResponseBytes(16)
                        .build())
                .build();

        final Throwable thrown;
        try (final GitHubClient client =
                GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, configuration)) {
            final HttpCall call = HttpCall.get(USER_PATH);

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(ResponseTooLargeException.class);
    }

    @Test
    void create_shouldSendRequests_whenOnlyConfigurationGiven() throws Exception {
        API.stub(GET, USER_PATH, status(OK));

        try (final GitHubClient client =
                GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, configuration())) {
            assertThat(get(client, USER_PATH).statusCode()).isEqualTo(OK.code());
        }
    }

    @Test
    void execute_shouldSendNoAuthorization_whenNone() throws Exception {
        API.stub(GET, USER_PATH, status(OK));

        try (final GitHubClient client =
                GitHubClients.create(AuthenticationProviders.none(), TestClients.USER_AGENT, configuration())) {
            assertThat(get(client, USER_PATH).statusCode()).isEqualTo(OK.code());
        }

        API.verifyNoHeader(GET, USER_PATH, AUTHORIZATION);
    }

    @Test
    void execute_shouldNotResend_whenNoneRejectedWith401() {
        API.stub(GET, USER_PATH, status(UNAUTHORIZED));

        final Throwable thrown;
        try (final GitHubClient client =
                GitHubClients.create(AuthenticationProviders.none(), TestClients.USER_AGENT, configuration())) {
            final HttpCall call = HttpCall.get(USER_PATH);

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
        API.verifyRequested(GET, USER_PATH, 1);
    }

    @Test
    void execute_shouldDropQueryFromExceptionUri_whenCrossOriginRedirectEndsInError() {
        OTHER.stub(GET, "/target", status(NOT_FOUND));
        API.stub(
                GET, "/start", status(FOUND.code()).header(LOCATION, OTHER.uri("/target") + "?X-Amz-Signature=secret"));

        final Throwable thrown;
        try (final GitHubClient client =
                GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, configuration())) {
            final HttpCall call = HttpCall.get("/start");

            thrown = catchThrowable(() -> client.execute(call));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        final URI uri = ((GitHubApiException) thrown).uri().orElseThrow();
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri).hasPath("/target");
    }

    @Test
    void createTokenClient_shouldSendAuthorizationUserAgentAndApiVersion_whenRequestSent() throws Exception {
        API.stub(GET, USER_PATH, status(OK));
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(configuration())
                .build();

        try (final GitHubClient client =
                GitHubClients.createTokenClient(TestClients.auth(), TestClients.USER_AGENT, clientConfiguration)) {
            get(client, USER_PATH);
        }

        API.verifyHeader(GET, USER_PATH, AUTHORIZATION, "Bearer " + TestClients.TOKEN);
        API.verifyHeader(GET, USER_PATH, USER_AGENT, TestClients.USER_AGENT);
        API.verifyHeader(GET, USER_PATH, GitHubHeaders.API_VERSION, GitHubClient.API_VERSION);
    }

    @Test
    void createTokenClient_shouldRetryRateLimitedRequest_whenRetryAfterGiven() throws Exception {
        API.stub(GET, USER_PATH, status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"), status(OK));
        final RetryConfiguration retry = RetryConfiguration.builder()
                .rateLimitJitter(Duration.ZERO)
                .clockSkewMargin(Duration.ZERO)
                .minRateLimitWait(Duration.ZERO)
                .build();
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(configuration())
                .retry(retry)
                .build();

        final HttpResponse response;
        try (final GitHubClient client =
                GitHubClients.createTokenClient(TestClients.auth(), TestClients.USER_AGENT, clientConfiguration)) {
            response = get(client, USER_PATH);
        }

        assertThat(response.statusCode()).isEqualTo(OK.code());
        API.verifyRequested(GET, USER_PATH, 2);
    }

    @Test
    void createTokenClient_shouldReportEachCallToObserver_whenCallObserverGiven() throws Exception {
        API.stub(GET, USER_PATH, status(OK));
        final List<HttpCallEvent> events = new CopyOnWriteArrayList<>();
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(configuration())
                .callObserver(events::add)
                .build();

        try (final GitHubClient client =
                GitHubClients.createTokenClient(TestClients.auth(), TestClients.USER_AGENT, clientConfiguration)) {
            get(client, USER_PATH);
            get(client, USER_PATH);
        }

        assertThat(events).hasSize(2);
    }

    @Test
    void createTokenClient_shouldReportQuotaToListener_whenRateLimitListenerGiven() throws Exception {
        API.stub(GET, USER_PATH, withQuota(status(OK), 4990));
        final List<GitHubRateLimitSnapshot> seen = new CopyOnWriteArrayList<>();
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(configuration())
                .rateLimitListener(seen::add)
                .build();

        try (final GitHubClient client =
                GitHubClients.createTokenClient(TestClients.auth(), TestClients.USER_AGENT, clientConfiguration)) {
            get(client, USER_PATH);
        }

        assertThat(seen)
                .singleElement()
                .satisfies(snapshot -> assertThat(snapshot.remaining()).isEqualTo(4990));
    }

    private static StubHttpServer.StubResponse withQuota(final StubHttpServer.StubResponse response, final int left) {
        return response.header(RATE_LIMIT_LIMIT, "5000")
                .header(RATE_LIMIT_REMAINING, String.valueOf(left))
                .header(RATE_LIMIT_USED, String.valueOf(5000 - left))
                .header(RATE_LIMIT_RESET, "1767225600")
                .header(RATE_LIMIT_RESOURCE, "core");
    }

    private static HttpClientConfiguration configuration() {
        return TestClients.configuration(URI.create(API.baseUri() + "/"));
    }

    private static HttpResponse get(final GitHubClient client, final String path) throws IOException {
        return client.execute(HttpCall.get(path));
    }
}
