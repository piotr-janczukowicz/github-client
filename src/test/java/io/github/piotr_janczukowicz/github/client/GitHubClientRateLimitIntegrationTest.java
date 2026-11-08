package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpStatus.NOT_FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.github.client.exception.GitHubNotFoundException;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

@IntegrationTest
@Isolated
class GitHubClientRateLimitIntegrationTest {

    @RegisterExtension
    static final StubHttpServer HTTP = new StubHttpServer();

    private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.UTF_8);

    private static StubHttpServer.StubResponse withQuota(final StubHttpServer.StubResponse response, final int left) {
        return response.header(RATE_LIMIT_LIMIT, "5000")
                .header(RATE_LIMIT_REMAINING, String.valueOf(left))
                .header(RATE_LIMIT_USED, String.valueOf(5000 - left))
                .header(RATE_LIMIT_RESET, "1767225600")
                .header(RATE_LIMIT_RESOURCE, "core");
    }

    private static DefaultGitHubClient.Builder builder(final HttpTransport transport) {
        return DefaultGitHubClient.builder(
                        transport,
                        TestClients.configurationBuilder(HTTP.baseUri()).build(),
                        TestClients.auth())
                .userAgent(TestClients.USER_AGENT);
    }

    @Test
    void lastRateLimit_shouldReportQuota_whenResponseIsSuccessful() throws Exception {
        HTTP.stub(GET, "/user", withQuota(status(OK).body(EMPTY_OBJECT), 4990));

        try (HttpTransport transport = HttpTransports.okHttp();
                GitHubClient client = builder(transport).build()) {
            assertThat(client.lastRateLimit("core")).isEmpty();

            client.execute(HttpCall.get("/user"));

            final GitHubRateLimitSnapshot snapshot =
                    client.lastRateLimit("core").orElseThrow();
            assertThat(snapshot.remaining()).isEqualTo(4990);
            assertThat(snapshot.limit()).isEqualTo(5000);
            assertThat(snapshot.resetAt()).isEqualTo(Instant.ofEpochSecond(1_767_225_600L));
            assertThat(client.lastRateLimits()).containsOnlyKeys("core");
        }
    }

    @Test
    void lastRateLimit_shouldBeUpdated_whenResponseIsAnError() {
        HTTP.stub(GET, "/missing", withQuota(status(NOT_FOUND), 4000));

        try (HttpTransport transport = HttpTransports.okHttp();
                GitHubClient client = builder(transport).build()) {
            final Throwable thrown = catchThrowable(() -> client.execute(HttpCall.get("/missing")));

            assertThat(thrown).isInstanceOf(GitHubNotFoundException.class);
            assertThat(client.lastRateLimit("core").orElseThrow().remaining()).isEqualTo(4000);
        }
    }

    @Test
    void listener_shouldReceiveQuotaOfEachResponse_whenBuiltWithListener() throws Exception {
        HTTP.stub(GET, "/user", withQuota(status(OK).body(EMPTY_OBJECT), 4990));
        final List<GitHubRateLimitSnapshot> seen = new CopyOnWriteArrayList<>();

        try (HttpTransport transport = HttpTransports.okHttp();
                GitHubClient client =
                        builder(transport).rateLimitListener(seen::add).build()) {
            client.execute(HttpCall.get("/user"));
            client.execute(HttpCall.get("/user"));
        }

        assertThat(seen).hasSize(2);
    }

    @Test
    void stream_shouldReportQuota_whenBodyIsStreamed() throws Exception {
        HTTP.stub(GET, "/archive", withQuota(status(OK).body(new byte[] {1, 2}), 3000));

        try (HttpTransport transport = HttpTransports.okHttp();
                GitHubClient client = builder(transport).build()) {
            client.stream(HttpCall.get("/archive"), response -> {
                response.body().readAllBytes();
                return null;
            });

            assertThat(client.lastRateLimit("core").orElseThrow().remaining()).isEqualTo(3000);
        }
    }

    @Test
    void lastRateLimit_shouldStayEmpty_whenResponseHasNoQuotaHeaders() throws Exception {
        HTTP.stub(GET, "/plain", status(OK).body(EMPTY_OBJECT));

        try (HttpTransport transport = HttpTransports.okHttp();
                GitHubClient client = builder(transport).build()) {
            client.execute(HttpCall.get("/plain"));

            assertThat(client.lastRateLimits()).isEmpty();
        }
    }
}
