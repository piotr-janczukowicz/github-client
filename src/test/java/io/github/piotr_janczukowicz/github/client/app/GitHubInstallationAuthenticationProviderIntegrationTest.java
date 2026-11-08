package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpMethod.POST;
import static io.github.piotr_janczukowicz.http.HttpStatus.CREATED;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.http.HttpStatus.UNAUTHORIZED;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.connectionReset;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.github.client.exception.GitHubNotFoundException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubUnauthorizedException;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

@IntegrationTest
@Isolated
class GitHubInstallationAuthenticationProviderIntegrationTest {

    private static final String TOKEN_PATH = "/app/installations/42/access_tokens";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @RegisterExtension
    static final StubHttpServer HTTP = new StubHttpServer();

    private static byte[] tokenBody(final String token, final Instant expiresAt) {
        return ("{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static StubHttpServer.StubResponse issued(final String token, final Instant expiresAt) {
        return status(CREATED).header(CONTENT_TYPE, "application/json").body(tokenBody(token, expiresAt));
    }

    private static String tokenOf(final GitHubInstallationAuthenticationProvider auth) {
        return auth.authentication().orElseThrow().value();
    }

    private static AuthFactory auth(final Clock clock) {
        return new AuthFactory(clock);
    }

    private static final class AuthFactory {
        private final Clock clock;
        private final GitHubInstallationAuthConfiguration.Builder configuration =
                GitHubInstallationAuthConfiguration.builder()
                        .appId("Iv1.test")
                        .privateKeyPem(AppKeys.pkcs1Pem())
                        .installationId(42);

        private AuthFactory(final Clock clock) {
            this.clock = clock;
        }

        AuthFactory refreshMargin(final Duration refreshMargin) {
            configuration.refreshMargin(refreshMargin);
            return this;
        }

        AuthFactory minRenewInterval(final Duration minRenewInterval) {
            configuration.minRenewInterval(minRenewInterval);
            return this;
        }

        AuthFactory repositories(final Collection<String> repositoryNames) {
            configuration.repositories(repositoryNames);
            return this;
        }

        AuthFactory repositoryIds(final Collection<Long> ids) {
            configuration.repositoryIds(ids);
            return this;
        }

        AuthFactory permissions(final Map<String, String> permissionLevels) {
            configuration.permissions(permissionLevels);
            return this;
        }

        GitHubInstallationAuthenticationProvider build() {
            return TestInstallationAuths.create(
                    HttpTransports.okHttp(), HttpClientConfiguration.of(HTTP.baseUri()), configuration.build(), clock);
        }
    }

    @Test
    void token_shouldFetchWithSignedJwtAndCacheIt() {
        HTTP.stub(POST, TOKEN_PATH, issued("ghs_first", NOW.plus(Duration.ofHours(1))));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
            final String first = auth.authentication().orElseThrow().value();
            final String second = auth.authentication().orElseThrow().value();

            assertSoftly(softly -> {
                softly.assertThat(first).isEqualTo("ghs_first");
                softly.assertThat(second).isEqualTo("ghs_first");
            });
        }

        HTTP.verifyRequested(POST, TOKEN_PATH, 1);
        HTTP.verifyHeader(
                POST,
                TOKEN_PATH,
                AUTHORIZATION,
                "Bearer " + GitHubAppJwts.create("Iv1.test", AppKeys.pair().getPrivate(), NOW));
    }

    @Test
    void token_shouldRenew_whenWithinRefreshMarginOfExpiry() {
        final MutableClock clock = new MutableClock(NOW);
        HTTP.stub(
                POST,
                TOKEN_PATH,
                issued("ghs_first", NOW.plus(Duration.ofHours(1))),
                issued("ghs_second", NOW.plus(Duration.ofHours(2))));

        try (GitHubInstallationAuthenticationProvider auth = auth(clock).build()) {
            final String first = auth.authentication().orElseThrow().value();
            clock.set(NOW.plus(Duration.ofMinutes(54)));
            final String stillCached = auth.authentication().orElseThrow().value();
            clock.set(NOW.plus(Duration.ofMinutes(56)));
            final String renewed = auth.authentication().orElseThrow().value();

            assertSoftly(softly -> {
                softly.assertThat(first).isEqualTo("ghs_first");
                softly.assertThat(stillCached).isEqualTo("ghs_first");
                softly.assertThat(renewed).isEqualTo("ghs_second");
            });
        }

        HTTP.verifyRequested(POST, TOKEN_PATH, 2);
    }

    @Test
    void isRenewableAfterRejection_shouldDropOnlyThatToken() {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                issued("ghs_first", NOW.plus(Duration.ofHours(1))),
                issued("ghs_second", NOW.plus(Duration.ofHours(1))));

        try (GitHubInstallationAuthenticationProvider auth = auth(Clock.fixed(NOW, ZoneOffset.UTC))
                .minRenewInterval(Duration.ZERO)
                .build()) {
            final boolean unknownRenewable =
                    auth.isRenewableAfterRejection(Authentication.ofBearer("nothing_cached_yet"));
            final String first = auth.authentication().orElseThrow().value();
            final boolean otherRenewable = auth.isRenewableAfterRejection(Authentication.ofBearer("some_other_token"));
            final String afterOther = auth.authentication().orElseThrow().value();
            final boolean staleRenewable = auth.isRenewableAfterRejection(Authentication.ofBearer(first));
            final String afterStale = auth.authentication().orElseThrow().value();
            final boolean repeatRenewable = auth.isRenewableAfterRejection(Authentication.ofBearer(first));
            final String afterRepeat = auth.authentication().orElseThrow().value();

            assertSoftly(softly -> {
                softly.assertThat(unknownRenewable).isTrue();
                softly.assertThat(first).isEqualTo("ghs_first");
                softly.assertThat(otherRenewable).isTrue();
                softly.assertThat(afterOther).isEqualTo("ghs_first");
                softly.assertThat(staleRenewable).isTrue();
                softly.assertThat(afterStale).isEqualTo("ghs_second");
                softly.assertThat(repeatRenewable).isTrue();
                softly.assertThat(afterRepeat).isEqualTo("ghs_second");
            });
        }

        HTTP.verifyRequested(POST, TOKEN_PATH, 2);
    }

    @Test
    void token_shouldFail_whenInterruptedWhileWaitingForAnotherThreadsFetch() throws Exception {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                issued("ghs_slow", NOW.plus(Duration.ofHours(1))).delay(Duration.ofSeconds(2)));

        try (GitHubInstallationAuthenticationProvider auth =
                        auth(Clock.fixed(NOW, ZoneOffset.UTC)).build();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            final Future<String> fetching = executor.submit(() -> tokenOf(auth));
            HTTP.awaitRequested(POST, TOKEN_PATH, Duration.ofSeconds(5));
            final AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
            final Thread waiter = new Thread(() -> waiterFailure.set(catchThrowable(auth::authentication)));
            waiter.start();
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            final boolean waiting = waiter.getState() == Thread.State.WAITING;
            waiter.interrupt();
            final boolean finished = waiter.join(Duration.ofSeconds(1));

            assertSoftly(softly -> {
                softly.assertThat(waiting).isTrue();
                softly.assertThat(finished).isTrue();
                softly.assertThat(waiterFailure.get())
                        .isInstanceOf(UncheckedIOException.class)
                        .hasCauseInstanceOf(InterruptedIOException.class);
            });
            assertThat(fetching.get(5, TimeUnit.SECONDS)).isEqualTo("ghs_slow");
        }
    }

    @Test
    void token_shouldFailWithInterruptedCause_whenThreadIsInterruptedBeforeFetching() {
        try (GitHubInstallationAuthenticationProvider auth =
                auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
            Thread.currentThread().interrupt();
            final Throwable thrown = catchThrowable(auth::authentication);
            final boolean stillInterrupted = Thread.interrupted();

            assertSoftly(softly -> {
                softly.assertThat(thrown)
                        .isInstanceOf(UncheckedIOException.class)
                        .hasCauseInstanceOf(InterruptedIOException.class);
                softly.assertThat(stillInterrupted).isTrue();
            });
        }

        HTTP.verifyRequested(POST, TOKEN_PATH, 0);
    }

    @Test
    void token_shouldRespectConfiguredRefreshMargin() {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                issued("ghs_first", NOW.plus(Duration.ofHours(1))),
                issued("ghs_second", NOW.plus(Duration.ofHours(1))));

        final MutableClock clock = new MutableClock(NOW);
        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock).refreshMargin(Duration.ofHours(1)).build()) {
            assertThat(auth.authentication().orElseThrow().value()).isEqualTo("ghs_first");
            clock.set(NOW.plus(Duration.ofMinutes(29)));
            assertThat(auth.authentication().orElseThrow().value()).isEqualTo("ghs_first");
            clock.set(NOW.plus(Duration.ofMinutes(31)));
            assertThat(auth.authentication().orElseThrow().value()).isEqualTo("ghs_second");
        }
    }

    @Test
    void token_shouldFetchOnlyOnce_whenManyThreadsAskAtTheSameTime() throws Exception {
        HTTP.stub(POST, TOKEN_PATH, issued("ghs_shared", NOW.plus(Duration.ofHours(1))));
        final int threads = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);

        final List<String> tokens = new ArrayList<>();
        try (GitHubInstallationAuthenticationProvider auth =
                auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
            final List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final Callable<String> call = () -> {
                    start.await();
                    return tokenOf(auth);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            for (final Future<String> future : futures) {
                tokens.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tokens).hasSize(threads).containsOnly("ghs_shared");
        HTTP.verifyRequested(POST, TOKEN_PATH, 1);
    }

    @Test
    void token_shouldRetryFailure_whenBackoffHasPassed() {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                status(UNAUTHORIZED).body("{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8)),
                issued("ghs_after", NOW.plus(Duration.ofHours(1))));

        final MutableClock clock = new MutableClock(NOW);
        try (GitHubInstallationAuthenticationProvider auth = auth(clock).build()) {
            final Throwable first = catchThrowable(auth::authentication);
            clock.set(NOW.plus(Duration.ofSeconds(1)));
            final String second = auth.authentication().orElseThrow().value();

            assertThat(first).isInstanceOf(GitHubUnauthorizedException.class);
            assertThat(second).isEqualTo("ghs_after");
        }
    }

    @Test
    void token_shouldSurfaceNotFound_whenInstallationUnknown() {
        HTTP.stub(POST, TOKEN_PATH, status(404));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
            final Throwable thrown = catchThrowable(auth::authentication);

            assertThat(thrown).isInstanceOf(GitHubNotFoundException.class);
        }
    }

    @Test
    void token_shouldWrapTransportFailureInUncheckedIOException() {
        HTTP.stub(POST, TOKEN_PATH, connectionReset());

        try (GitHubInstallationAuthenticationProvider auth =
                auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
            final Throwable thrown = catchThrowable(auth::authentication);

            assertThat(thrown).isInstanceOf(UncheckedIOException.class);
        }
    }

    @Test
    void token_shouldFail_whenResponseHasNoBody() {
        HTTP.stub(POST, TOKEN_PATH, status(CREATED));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
            final Throwable thrown = catchThrowable(auth::authentication);

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("no installation token");
        }
    }

    @Test
    void token_shouldFail_whenResponseLacksTokenOrExpiry() {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                status(CREATED)
                        .header(CONTENT_TYPE, "application/json")
                        .body("{\"token\":\"x\"}".getBytes(StandardCharsets.UTF_8)),
                status(CREATED)
                        .header(CONTENT_TYPE, "application/json")
                        .body("{\"token\":\" \",\"expires_at\":\"2030-01-01T00:00:00Z\"}"
                                .getBytes(StandardCharsets.UTF_8)),
                status(CREATED)
                        .header(CONTENT_TYPE, "application/json")
                        .body("{\"expires_at\":\"2030-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8)));

        for (int response = 0; response < 3; response++) {
            try (GitHubInstallationAuthenticationProvider auth =
                    auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
                final Throwable thrown = catchThrowable(auth::authentication);

                assertThat(thrown).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void auth_shouldAuthenticateAGitHubClientWithTheInstallationToken() throws Exception {
        HTTP.stub(POST, TOKEN_PATH, issued("ghs_for_client", NOW.plus(Duration.ofHours(1))));
        HTTP.stub(GET, "/user", status(OK).body("{}".getBytes(StandardCharsets.UTF_8)));

        try (GitHubInstallationAuthenticationProvider auth =
                        auth(Clock.fixed(NOW, ZoneOffset.UTC)).build();
                GitHubClient client = githubClient(auth)) {
            client.execute(HttpCall.get("/user"));
        }

        HTTP.verifyHeader(GET, "/user", AUTHORIZATION, "Bearer ghs_for_client");
    }

    @Test
    void auth_shouldRenewTokenAndSendTheCallAgain_whenGitHubRejectsTheCachedToken() throws Exception {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                issued("ghs_revoked", NOW.plus(Duration.ofHours(1))),
                issued("ghs_renewed", NOW.plus(Duration.ofHours(1))));
        HTTP.stub(
                GET,
                "/user",
                status(UNAUTHORIZED).body("{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8)),
                status(OK).body("{}".getBytes(StandardCharsets.UTF_8)));

        try (GitHubInstallationAuthenticationProvider auth = auth(Clock.fixed(NOW, ZoneOffset.UTC))
                        .minRenewInterval(Duration.ZERO)
                        .build();
                GitHubClient client = githubClient(auth)) {
            final int status = client.execute(HttpCall.get("/user")).statusCode();

            assertThat(status).isEqualTo(OK.code());
        }

        HTTP.verifyRequested(POST, TOKEN_PATH, 2);
        HTTP.verifyRequested(GET, "/user", 2);
        HTTP.verifyHeader(GET, "/user", AUTHORIZATION, "Bearer ghs_revoked");
        HTTP.verifyHeader(GET, "/user", AUTHORIZATION, "Bearer ghs_renewed");
    }

    @Test
    void auth_shouldSurfaceTheSecondRejection_whenTheRenewedTokenIsRejectedToo() {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                issued("ghs_one", NOW.plus(Duration.ofHours(1))),
                issued("ghs_two", NOW.plus(Duration.ofHours(1))),
                issued("ghs_three", NOW.plus(Duration.ofHours(1))));
        HTTP.stub(
                GET,
                "/user",
                status(UNAUTHORIZED).body("{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8)));

        try (GitHubInstallationAuthenticationProvider auth = auth(Clock.fixed(NOW, ZoneOffset.UTC))
                        .minRenewInterval(Duration.ZERO)
                        .build();
                GitHubClient client = githubClient(auth)) {
            final HttpCall userRequest = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(userRequest));

            assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
        }

        HTTP.verifyRequested(GET, "/user", 2);
        HTTP.verifyRequested(POST, TOKEN_PATH, 2);
    }

    @Test
    void auth_shouldSurfaceTheRejection_whenTheTokenIsTooYoungToRenew() {
        HTTP.stub(POST, TOKEN_PATH, issued("ghs_young", NOW.plus(Duration.ofHours(1))));
        HTTP.stub(
                GET,
                "/user",
                status(UNAUTHORIZED).body("{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8)));

        try (GitHubInstallationAuthenticationProvider auth =
                        auth(Clock.fixed(NOW, ZoneOffset.UTC)).build();
                GitHubClient client = githubClient(auth)) {
            final HttpCall userRequest = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(userRequest));

            assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
        }

        HTTP.verifyRequested(GET, "/user", 1);
        HTTP.verifyRequested(POST, TOKEN_PATH, 1);
    }

    @Test
    void auth_shouldNotSendTheCallAgain_whenTheTokenIsFixed() {
        HTTP.stub(
                GET,
                "/user",
                status(UNAUTHORIZED).body("{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8)));

        try (GitHubClient client = githubClient(AuthenticationProviders.bearer("ghp_fixed"))) {
            final HttpCall userRequest = HttpCall.get("/user");

            final Throwable thrown = catchThrowable(() -> client.execute(userRequest));

            assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
        }

        HTTP.verifyRequested(GET, "/user", 1);
    }

    private static GitHubClient githubClient(final AuthenticationProvider auth) {
        return TestGitHubClients.raw(HttpTransports.okHttp(), HttpClientConfiguration.of(HTTP.baseUri()), auth);
    }

    @Test
    void token_shouldRequestTheConfiguredScope_whenRepositoriesAndPermissionsAreSet() {
        HTTP.stub(POST, TOKEN_PATH, issued("ghs_scoped", NOW.plus(Duration.ofHours(1))));

        try (GitHubInstallationAuthenticationProvider auth = auth(Clock.fixed(NOW, ZoneOffset.UTC))
                .repositories(List.of("repo-a", "repo-b"))
                .repositoryIds(List.of(5L))
                .permissions(Map.of("issues", "write", "contents", "read"))
                .build()) {
            assertThat(auth.authentication().orElseThrow().value()).isEqualTo("ghs_scoped");
        }

        HTTP.verifyBody(
                POST,
                TOKEN_PATH,
                "{\"repositories\":[\"repo-a\",\"repo-b\"],\"repository_ids\":[5],"
                        + "\"permissions\":{\"contents\":\"read\",\"issues\":\"write\"}}");
        HTTP.verifyHeader(POST, TOKEN_PATH, CONTENT_TYPE, "application/json");
    }

    @Test
    void token_shouldSendNoBody_whenNoScopeIsSet() {
        HTTP.stub(POST, TOKEN_PATH, issued("ghs_all", NOW.plus(Duration.ofHours(1))));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(Clock.fixed(NOW, ZoneOffset.UTC)).build()) {
            auth.authentication().orElseThrow().value();
        }

        HTTP.verifyAbsentBody(POST, TOKEN_PATH);
    }
}
