package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.API_VERSION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.USER_AGENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpCallObserver;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.test.http.ScriptedHttpServer;
import io.github.piotr_janczukowicz.test.http.ScriptedTransport;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@UnitTest
@Isolated
class GitHubClientsTest {

    private static final URI API = URI.create("https://api.github.com/");
    private static final HttpClientConfiguration CONFIGURATION = TestClients.configuration(API);
    private static final HttpCall USER = HttpCall.get("/user");
    private static final HttpCall POST_ISSUE = HttpCall.of(HttpMethod.POST, "/repos/owner/repo/issues");
    private static final Duration PACED_INTERVAL = Duration.ofMillis(300);
    private static final Duration TIMER_SLACK = Duration.ofMillis(5);
    private static final Duration PACED_POST_HEAD_START = Duration.ofMillis(100);
    private static final Duration HOLD = Duration.ofMillis(20);
    private static final int WAIT_SECONDS = 10;
    private static final int SPINS = 2_000;

    @Test
    void create_shouldTargetGitHubDotCom_whenOnlyAuthAndUserAgentGiven() {
        final GitHubClient client = GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT);

        try (client) {
            assertThat(client.baseUri()).isEqualTo(GitHubClientConfiguration.DEFAULT_BASE_URI);
        }
    }

    @Test
    void create_shouldUseConfiguration_whenConfigurationGiven() {
        final GitHubClient client = GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, CONFIGURATION);

        try (client) {
            assertThat(client.baseUri()).isEqualTo(CONFIGURATION.baseUri());
        }
    }

    @Test
    void create_shouldUsePagination_whenConfigurationHasPagination() {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(2).perPage(30).build();
        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().pagination(pagination).build();

        final GitHubClient client = GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, configuration);

        try (client) {
            assertThat(client.pagination()).isEqualTo(pagination);
        }
    }

    @Test
    void create_shouldExposeFlavor_whenConfigurationHasFlavor() {
        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().flavor(GitHubFlavor.GHEC).build();
        final GitHubClient client = GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, configuration);

        try (client) {
            assertThat(client.flavor()).contains(GitHubFlavor.GHEC);
        }
    }

    @Test
    void create_shouldExposeFlavorAndVersion_whenConfigurationIsGhes() {
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(TestHosts.GHES))
                .flavor(GitHubFlavor.GHES)
                .ghesVersion(new GhesVersion(3, 22))
                .build();
        final GitHubClient client = GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, configuration);

        try (client) {
            assertSoftly(softly -> {
                softly.assertThat(client.flavor()).contains(GitHubFlavor.GHES);
                softly.assertThat(client.ghesVersion()).contains(new GhesVersion(3, 22));
            });
        }
    }

    @Test
    void create_shouldExposeNoVersion_whenConfigurationHasNone() {
        final GitHubClient client =
                GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, GitHubClientConfiguration.defaults());

        try (client) {
            assertThat(client.ghesVersion()).isEmpty();
        }
    }

    @Test
    void create_shouldExposeNoFlavor_whenConfigurationHasNone() {
        final GitHubClient client =
                GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, GitHubClientConfiguration.defaults());

        try (client) {
            assertThat(client.flavor()).isEmpty();
        }
    }

    @Test
    void createTokenClient_shouldExposeFlavor_whenConfigurationHasFlavor() {
        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().flavor(GitHubFlavor.CLOUD).build();
        final GitHubClient client =
                GitHubClients.createTokenClient(TestClients.auth(), TestClients.USER_AGENT, configuration);

        try (client) {
            assertThat(client.flavor()).contains(GitHubFlavor.CLOUD);
        }
    }

    @Test
    void create_shouldRefusePlainHttp_whenBaseUriIsNotLoopback() {
        final HttpClientConfiguration plain = HttpClientConfiguration.builder(
                        URI.create("http://ghe.example.test/api/v3/"))
                .build();

        final Throwable thrown =
                catchThrowable(() -> GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, plain));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("allowInsecureHttp");
    }

    @Test
    void create_shouldRejectNullSettings_whenConfigurationIsNull() {
        final Throwable thrown = catchThrowable(() ->
                GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, (GitHubClientConfiguration) null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    @Test
    void create_shouldRejectNullAuth_whenAuthIsNull() {
        final Throwable thrown = catchThrowable(() -> GitHubClients.create(null, TestClients.USER_AGENT));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("auth");
    }

    @Test
    void create_shouldRejectBlankUserAgent_whenUserAgentIsBlank() {
        final Throwable thrown = catchThrowable(() -> GitHubClients.create(TestClients.auth(), " "));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userAgent");
    }

    @Test
    void createTokenClient_shouldRejectNullAuth_whenAuthIsNull() {
        final Throwable thrown = catchThrowable(() ->
                GitHubClients.createTokenClient(null, TestClients.USER_AGENT, GitHubClientConfiguration.defaults()));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("auth");
    }

    @Test
    void createTokenClient_shouldRejectNullConfiguration_whenConfigurationIsNull() {
        final Throwable thrown =
                catchThrowable(() -> GitHubClients.createTokenClient(TestClients.auth(), TestClients.USER_AGENT, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    @Test
    void createTokenClient_shouldRejectBlankUserAgent_whenUserAgentIsBlank() {
        final Throwable thrown = catchThrowable(
                () -> GitHubClients.createTokenClient(TestClients.auth(), " ", GitHubClientConfiguration.defaults()));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userAgent");
    }

    @Test
    void createTokenClient_shouldRejectUserAgentDefaultHeader_whenDefaultHeaderSetByClient() {
        final HttpClientConfiguration withUserAgent = HttpClientConfiguration.builder(API)
                .defaultHeaders(HttpHeaders.of(USER_AGENT, "other-agent/2.0"))
                .build();
        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().configuration(withUserAgent).build();

        final Throwable thrown = catchThrowable(
                () -> GitHubClients.createTokenClient(TestClients.auth(), TestClients.USER_AGENT, configuration));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(USER_AGENT);
    }

    @Test
    void assemble_shouldCloseTransport_whenDefaultHeaderSetByClient() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final HttpClientConfiguration withVersion = HttpClientConfiguration.builder(API)
                .defaultHeaders(HttpHeaders.of(API_VERSION, "2022-11-28"))
                .build();
        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().configuration(withVersion).build();

        final Throwable thrown = catchThrowable(() -> GitHubClients.assemble(
                transport,
                TestClients.auth(),
                TestClients.USER_AGENT,
                configuration,
                TestGitHubClients.TEST_MUTATION_INTERVAL,
                GitHubClientsTest::retryingOver));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(API_VERSION);
        verify(transport).close();
    }

    @Test
    void assemble_shouldCloseTransport_whenMutationIntervalRejected() {
        final HttpTransport transport = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(() -> GitHubClients.assemble(
                transport,
                TestClients.auth(),
                TestClients.USER_AGENT,
                GitHubClientConfiguration.defaults(),
                Duration.ofSeconds(-1),
                GitHubClientsTest::retryingOver));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(transport).close();
    }

    @Test
    void assemble_shouldCloseTransport_whenBuildRefusesPlainHttp() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final GitHubClientConfiguration plain = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.builder(URI.create("http://ghe.example.test/api/v3/"))
                        .build())
                .build();
        final DefaultGitHubClient.Builder builder = GitHubClients.assemble(
                transport,
                TestClients.auth(),
                TestClients.USER_AGENT,
                plain,
                TestGitHubClients.TEST_MUTATION_INTERVAL,
                GitHubClientsTest::retryingOver);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("allowInsecureHttp");
        verify(transport).close();
    }

    @Test
    void assemble_shouldRunOneRequestAtATime_whenDefaultConfiguration() throws Exception {
        final int callers = 8;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        try (ScriptedHttpServer server = new ScriptedHttpServer((call, exchange) -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            for (int spin = 0; spin < SPINS; spin++) {
                Thread.onSpinWait();
            }
            inFlight.decrementAndGet();
            exchange.sendResponseHeaders(200, -1);
        })) {
            final GitHubClient client = TestGitHubClients.defaultStack(
                    HttpTransports.okHttp(), local(server).build());
            final ExecutorService executor = Executors.newFixedThreadPool(callers);
            final List<Future<HttpResponse>> results = new ArrayList<>();

            try (client) {
                for (int i = 0; i < callers; i++) {
                    results.add(executor.submit(() -> client.execute(USER)));
                }
                for (final Future<HttpResponse> result : results) {
                    result.get(WAIT_SECONDS, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(peak.get()).isEqualTo(1);
        }
    }

    @Test
    void assemble_shouldAllowTenRequestsInFlight_whenMaxConcurrentRequestsIsTen() throws Exception {
        final int callers = 60;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final CountDownLatch full = new CountDownLatch(10);
        try (ScriptedHttpServer server = new ScriptedHttpServer((call, exchange) -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            full.countDown();
            try {
                full.await(2, TimeUnit.SECONDS);
                holdFor(HOLD);
            } finally {
                inFlight.decrementAndGet();
            }
            exchange.sendResponseHeaders(200, -1);
        })) {
            final GitHubClientConfiguration ten = local(server)
                    .transport(HttpTransportConfiguration.builder()
                            .maxConcurrentRequests(10)
                            .build())
                    .build();
            final GitHubClient client = TestGitHubClients.defaultStack(HttpTransports.okHttp(), ten);
            final ExecutorService executor = Executors.newFixedThreadPool(callers);
            final List<Future<HttpResponse>> results = new ArrayList<>();

            try (client) {
                for (int i = 0; i < callers; i++) {
                    results.add(executor.submit(() -> client.execute(USER)));
                }
                for (final Future<HttpResponse> result : results) {
                    result.get(30, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(peak.get()).isEqualTo(10);
        }
    }

    @Test
    void defaultStack_shouldRetrySecondaryLimit_whenForbiddenSecondaryBodyGiven() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN), RateLimitResponses.ok());
        final HttpResponse response;

        try (final GitHubClient client = TestGitHubClients.defaultStack(transport)) {
            response = client.execute(USER);
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(transport, times(2)).execute(any());
    }

    @Test
    void defaultStack_shouldRetryRateLimitedResponse_whenRetryAfterGiven() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(429, HttpHeaders.of(RETRY_AFTER, "1"), HttpBody.zeroLength()))
                .thenReturn(new HttpResponse(200, HttpHeaders.empty(), HttpBody.zeroLength()));
        final HttpResponse response;

        try (final GitHubClient client = TestGitHubClients.defaultStack(transport)) {
            response = client.execute(USER);
        }

        assertThat(response.statusCode()).isEqualTo(200);
        verify(transport, times(2)).execute(any());
    }

    @Test
    void defaultStack_shouldSpacePostsByInterval_whenPostsFollowEachOther() throws IOException {
        final ScriptedTransport transport = ScriptedTransport.replying();
        final GitHubClient client = TestGitHubClients.defaultStack(
                transport.transport(), GitHubClientConfiguration.defaults(), PACED_INTERVAL);

        try (client) {
            client.execute(POST_ISSUE);
            client.execute(POST_ISSUE);
        }

        assertThat(Duration.ofNanos(
                        transport.startNanos().get(1) - transport.startNanos().get(0)))
                .isGreaterThanOrEqualTo(PACED_INTERVAL.minus(TIMER_SLACK));
    }

    @Test
    void defaultStack_shouldNotSpacePosts_whenIntervalIsZero() throws IOException {
        final ScriptedTransport transport = ScriptedTransport.replying();
        final GitHubClient client = TestGitHubClients.defaultStack(
                transport.transport(), GitHubClientConfiguration.defaults(), Duration.ZERO);

        try (client) {
            client.execute(POST_ISSUE);
            client.execute(POST_ISSUE);
        }

        assertThat(Duration.ofNanos(
                        transport.startNanos().get(1) - transport.startNanos().get(0)))
                .isLessThan(PACED_INTERVAL);
    }

    @Test
    void defaultStack_shouldNotDelayGets_whenGetsFollowEachOther() throws IOException {
        final ScriptedTransport transport = ScriptedTransport.replying();
        final GitHubClient client = TestGitHubClients.defaultStack(
                transport.transport(), GitHubClientConfiguration.defaults(), PACED_INTERVAL);

        try (client) {
            client.execute(USER);
            client.execute(USER);
        }

        assertThat(Duration.ofNanos(
                        transport.startNanos().get(1) - transport.startNanos().get(0)))
                .isLessThan(PACED_INTERVAL);
    }

    @Test
    void defaultStack_shouldRunGetBeforePacedPost_whenOneSlotAndPostWaitsForInterval() throws Exception {
        final ScriptedTransport transport = ScriptedTransport.replying();
        final GitHubClientConfiguration oneSlot = GitHubClientConfiguration.builder()
                .transport(HttpTransportConfiguration.builder()
                        .maxConcurrentRequests(1)
                        .build())
                .build();
        final GitHubClient client = TestGitHubClients.defaultStack(transport.transport(), oneSlot, PACED_INTERVAL);
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        try (client) {
            client.execute(POST_ISSUE);
            final Future<HttpResponse> pacedPost = executor.submit(() -> client.execute(POST_ISSUE));
            holdFor(PACED_POST_HEAD_START);
            client.execute(USER);
            pacedPost.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(methods(transport)).containsExactly(HttpMethod.POST, HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    void defaultStack_shouldPaceRetriedPost_whenRetryAfterGiven() throws IOException {
        final ScriptedTransport transport = ScriptedTransport.replying(
                new HttpResponse(429, HttpHeaders.of(RETRY_AFTER, "1"), HttpBody.zeroLength()));
        final GitHubClient client = TestGitHubClients.defaultStack(
                transport.transport(), GitHubClientConfiguration.defaults(), PACED_INTERVAL);
        final HttpResponse response;

        try (client) {
            response = client.execute(POST_ISSUE);
        }

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(200);
            softly.assertThat(methods(transport)).containsExactly(HttpMethod.POST, HttpMethod.POST);
            softly.assertThat(Duration.ofNanos(transport.startNanos().get(1)
                            - transport.startNanos().get(0)))
                    .isGreaterThanOrEqualTo(PACED_INTERVAL.minus(TIMER_SLACK));
        });
    }

    @Test
    void close_shouldCloseTransportOnce_whenRootClientClosed() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final GitHubClient client = TestGitHubClients.raw(transport);

        client.close();

        verify(transport, times(1)).close();
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubClients> constructor = GitHubClients.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static void holdFor(final Duration duration) {
        final long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static HttpTransport retryingOver(final HttpTransport delegate, final RetryConfiguration retry) {
        return GitHubClients.retrying(delegate, retry);
    }

    private static List<HttpMethod> methods(final ScriptedTransport transport) {
        return transport.requests().stream().map(HttpRequest::method).toList();
    }

    private static GitHubClientConfiguration.Builder local(final ScriptedHttpServer server) {
        return GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.builder(server.uri()).build())
                .allowInsecureHttp(true);
    }

    @Test
    void create_shouldReportToListenerAndObserver_whenConfigurationSetThem() {
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.builder()
                .rateLimitListener(mock(GitHubRateLimitListener.class))
                .callObserver(mock(HttpCallObserver.class))
                .build();

        try (final GitHubClient client =
                GitHubClients.create(TestClients.auth(), TestClients.USER_AGENT, configuration)) {
            assertThat(client.baseUri()).isEqualTo(GitHubClientConfiguration.DEFAULT_BASE_URI);
        }
    }
}
