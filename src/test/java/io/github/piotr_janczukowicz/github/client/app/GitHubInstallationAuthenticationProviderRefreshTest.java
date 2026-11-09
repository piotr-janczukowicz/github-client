package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubUnauthorizedException;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.ScriptedTransport;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@UnitTest
@Isolated
class GitHubInstallationAuthenticationProviderRefreshTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);
    private static final int THREADS = 16;
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Test
    void token_shouldFetchOnce_whenSixteenThreadsAskOnAColdCache() throws Exception {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(request -> issued("ghs_cold", NOW.plus(HOUR)));
        transport.withDelay(Duration.ofMillis(200));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            final List<String> tokens = callTokenConcurrently(auth, THREADS);

            assertThat(tokens).hasSize(THREADS).containsOnly("ghs_cold");
        }
        assertThat(transport.calls()).isEqualTo(1);
    }

    @Test
    void token_shouldThrowFreshExceptionPerThreadAndNotRefetch_whenInsideBackoff() throws Exception {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(request -> failure(503));
        transport.withDelay(Duration.ofMillis(200));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            final List<Throwable> failures = callTokenConcurrentlyExpectingFailure(auth, THREADS);
            final Throwable again = catchThrowable(auth::authentication);

            final List<Throwable> unavailable = failures.stream()
                    .filter(GitHubInstallationTokenUnavailableException.class::isInstance)
                    .toList();
            final List<Throwable> original = failures.stream()
                    .filter(GitHubApiException.class::isInstance)
                    .toList();
            final Instant retryAt = NOW.plus(Duration.ofSeconds(1));
            assertSoftly(softly -> {
                softly.assertThat(failures).hasSize(THREADS);
                softly.assertThat(new HashSet<>(failures)).hasSize(THREADS);
                softly.assertThat(original).hasSize(1);
                softly.assertThat(unavailable).hasSize(THREADS - 1);
                softly.assertThat(unavailable).extracting(Throwable::getCause).containsOnly(original.getFirst());
                softly.assertThat(unavailable).allSatisfy(f -> assertThat(f).hasMessageContaining(retryAt.toString()));
                softly.assertThat(again)
                        .isInstanceOf(GitHubInstallationTokenUnavailableException.class)
                        .hasCause(original.getFirst());
                softly.assertThat(failures).doesNotContain(again);
                softly.assertThat(((GitHubInstallationTokenUnavailableException) again).retryAt())
                        .isEqualTo(retryAt);
                softly.assertThat(transport.calls()).isEqualTo(1);
            });
        }
    }

    @Test
    void token_shouldThrowNewExceptionOnEachCallWithoutLeakingResponseBody_whenInsideBackoff() {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(request -> failure(503));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            final Throwable first = catchThrowable(auth::authentication);
            final Throwable second = catchThrowable(auth::authentication);
            final Throwable third = catchThrowable(auth::authentication);

            assertSoftly(softly -> {
                softly.assertThat(first).isInstanceOf(GitHubApiException.class);
                softly.assertThat(second).isInstanceOf(GitHubInstallationTokenUnavailableException.class);
                softly.assertThat(third).isInstanceOf(GitHubInstallationTokenUnavailableException.class);
                softly.assertThat(second).isNotSameAs(third);
                softly.assertThat(second.getCause()).isSameAs(first);
                softly.assertThat(third.getCause()).isSameAs(first);
                softly.assertThat(second.getMessage()).doesNotContain("unavailable\"");
                softly.assertThat(transport.calls()).isEqualTo(1);
            });
        }
    }

    @Test
    void token_shouldRetryWithDoublingBackoffCappedAtMax_whenFailureRepeats() {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(request -> failure(503));

        try (GitHubInstallationAuthenticationProvider auth = auth(clock, transport.transport())
                .failureBackoff(Duration.ofSeconds(1), Duration.ofSeconds(3))
                .build()) {
            catchThrowable(auth::authentication);
            clock.advance(Duration.ofMillis(999));
            catchThrowable(auth::authentication);
            final int insideFirst = transport.calls();
            clock.advance(Duration.ofMillis(1));
            catchThrowable(auth::authentication);
            final int afterFirst = transport.calls();
            clock.advance(Duration.ofMillis(1999));
            catchThrowable(auth::authentication);
            final int insideSecond = transport.calls();
            clock.advance(Duration.ofMillis(1));
            catchThrowable(auth::authentication);
            final int afterSecond = transport.calls();
            clock.advance(Duration.ofMillis(2999));
            catchThrowable(auth::authentication);
            final int insideCapped = transport.calls();
            clock.advance(Duration.ofMillis(1));
            catchThrowable(auth::authentication);

            assertSoftly(softly -> {
                softly.assertThat(insideFirst).isEqualTo(1);
                softly.assertThat(afterFirst).isEqualTo(2);
                softly.assertThat(insideSecond).isEqualTo(2);
                softly.assertThat(afterSecond).isEqualTo(3);
                softly.assertThat(insideCapped).isEqualTo(3);
                softly.assertThat(transport.calls()).isEqualTo(4);
            });
        }
    }

    @Test
    void token_shouldResetBackoff_whenSucceeds() {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger attempt = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(request -> {
            final int n = attempt.incrementAndGet();
            return n == 2 ? issued("ghs_ok", clock.instant().plus(Duration.ofMinutes(10))) : failure(503);
        });

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            catchThrowable(auth::authentication);
            clock.advance(Duration.ofSeconds(1));
            final String token = auth.authentication().orElseThrow().value();
            clock.advance(Duration.ofMinutes(10));
            catchThrowable(auth::authentication);
            clock.advance(Duration.ofSeconds(1));
            catchThrowable(auth::authentication);

            assertThat(token).isEqualTo("ghs_ok");
            assertThat(transport.calls()).isEqualTo(4);
        }
    }

    @Test
    void token_shouldReturnUnexpiredToken_whenRenewalFailsAndNotRetryInsideBackoff() {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger attempt = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(
                request -> attempt.getAndIncrement() == 0 ? issued("ghs_old", NOW.plus(HOUR)) : failure(503));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            auth.authentication().orElseThrow().value();
            clock.set(NOW.plus(Duration.ofMinutes(56)));
            final String duringFailure = auth.authentication().orElseThrow().value();
            final String insideBackoff = auth.authentication().orElseThrow().value();

            assertSoftly(softly -> {
                softly.assertThat(duringFailure).isEqualTo("ghs_old");
                softly.assertThat(insideBackoff).isEqualTo("ghs_old");
                softly.assertThat(transport.calls()).isEqualTo(2);
            });
            clock.set(NOW.plus(HOUR).plusSeconds(1));
            final Throwable thrown = catchThrowable(auth::authentication);

            assertThat(thrown).isInstanceOf(GitHubApiException.class);
        }
    }

    @Test
    void token_shouldReturnUnexpiredTokenWithoutThrowingOrFetching_whenInMarginAndBackoffIsOpen() {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger attempt = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(
                request -> attempt.getAndIncrement() == 0 ? issued("ghs_margin", NOW.plus(HOUR)) : failure(503));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            auth.authentication().orElseThrow().value();
            clock.set(NOW.plus(Duration.ofMinutes(56)));
            final String renewalFailed = auth.authentication().orElseThrow().value();
            final List<Throwable> thrown = new ArrayList<>();
            final List<String> inBackoff = new ArrayList<>();
            for (int call = 0; call < 100; call++) {
                final AtomicReference<@Nullable String> value = new AtomicReference<>();
                final Throwable error = catchThrowable(() -> value.set(tokenOf(auth)));
                if (error != null) {
                    thrown.add(error);
                }
                inBackoff.add(value.get());
            }
            final int callsInBackoff = transport.calls();
            clock.advance(Duration.ofSeconds(1));
            final Throwable afterBackoff = catchThrowable(auth::authentication);

            assertSoftly(softly -> {
                softly.assertThat(renewalFailed).isEqualTo("ghs_margin");
                softly.assertThat(thrown).isEmpty();
                softly.assertThat(inBackoff).hasSize(100).containsOnly("ghs_margin");
                softly.assertThat(callsInBackoff).isEqualTo(2);
                softly.assertThat(afterBackoff).isNull();
                softly.assertThat(transport.calls()).isEqualTo(3);
            });
        }
    }

    @Test
    void token_shouldBlockOnlyOneThread_whenTokenIsValidButWithinMargin() throws Exception {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger attempt = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(request -> attempt.getAndIncrement() == 0
                ? issued("ghs_old", NOW.plus(HOUR))
                : issued("ghs_new", NOW.plus(HOUR).plus(HOUR)));

        try (GitHubInstallationAuthenticationProvider auth =
                        auth(clock, transport.transport()).build();
                ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            auth.authentication().orElseThrow().value();
            clock.set(NOW.plus(Duration.ofMinutes(56)));
            final CountDownLatch gate = new CountDownLatch(1);
            transport.gatedFromCall(2, gate);
            final Future<String> refresher = pool.submit(() -> tokenOf(auth));
            assertThat(transport.awaitCall(2, Duration.ofSeconds(5))).isTrue();

            final List<Future<String>> others = new ArrayList<>();
            for (int i = 0; i < THREADS - 1; i++) {
                others.add(pool.submit(() -> tokenOf(auth)));
            }
            final List<String> immediate = new ArrayList<>();
            for (final Future<String> other : others) {
                immediate.add(other.get(5, TimeUnit.SECONDS));
            }
            final boolean refresherStillWaiting = !refresher.isDone();
            gate.countDown();
            final String refreshed = refresher.get(5, TimeUnit.SECONDS);
            final String afterRefresh = tokenOf(auth);

            assertSoftly(softly -> {
                softly.assertThat(immediate).hasSize(THREADS - 1).containsOnly("ghs_old");
                softly.assertThat(refresherStillWaiting).isTrue();
                softly.assertThat(refreshed).isEqualTo("ghs_new");
                softly.assertThat(afterRefresh).isEqualTo("ghs_new");
                softly.assertThat(transport.calls()).isEqualTo(2);
            });
        }
    }

    @Test
    void token_shouldFetchAtMostOncePerHalfLifetime_whenMarginIsAsLongAsLifetime() {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(
                request -> issued("ghs_" + clock.instant(), clock.instant().plus(HOUR)));

        try (GitHubInstallationAuthenticationProvider auth = auth(clock, transport.transport())
                .refreshMargin(Duration.ofHours(2))
                .build()) {
            for (int minute = 0; minute < 30; minute++) {
                clock.set(NOW.plus(Duration.ofMinutes(minute)));
                auth.authentication().orElseThrow().value();
            }
            final int firstHalf = transport.calls();
            clock.set(NOW.plus(Duration.ofMinutes(30)));
            auth.authentication().orElseThrow().value();

            assertThat(firstHalf).isEqualTo(1);
            assertThat(transport.calls()).isEqualTo(2);
        }
    }

    @Test
    void token_shouldAcceptFreshShortLivedTokenAndShrinkTheMargin() {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(
                request -> issued("ghs_" + clock.instant(), clock.instant().plus(Duration.ofMinutes(2))));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            auth.authentication().orElseThrow().value();
            clock.advance(Duration.ofSeconds(59));
            auth.authentication().orElseThrow().value();
            final int insideHalf = transport.calls();
            clock.advance(Duration.ofSeconds(2));
            auth.authentication().orElseThrow().value();

            assertThat(insideHalf).isEqualTo(1);
            assertThat(transport.calls()).isEqualTo(2);
        }
    }

    @Test
    void token_shouldReturnTokenWithoutCaching_whenAlreadyExpired() {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(
                request -> issued("ghs_" + uniqueSuffix(), NOW.minus(Duration.ofMinutes(1))));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            final String first = auth.authentication().orElseThrow().value();
            final String second = auth.authentication().orElseThrow().value();

            assertThat(first).isNotEqualTo(second);
            assertThat(transport.calls()).isEqualTo(2);
        }
    }

    @Test
    void token_shouldNotRememberFetchAsFailure_whenInterrupted() {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger attempt = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(request -> {
            if (attempt.getAndIncrement() == 0) {
                Thread.currentThread().interrupt();
                throw new UncheckedIOException(new InterruptedIOException("interrupted"));
            }
            return issued("ghs_after", NOW.plus(HOUR));
        });

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            final Throwable first = catchThrowable(auth::authentication);
            final boolean wasInterrupted = Thread.interrupted();
            final String second = auth.authentication().orElseThrow().value();

            assertThat(first).isInstanceOf(UncheckedIOException.class);
            assertThat(wasInterrupted).isTrue();
            assertThat(second).isEqualTo("ghs_after");
        }
    }

    @Test
    void token_shouldBackOff_whenFetchTimesOutWithoutThreadInterrupt() {
        final MutableClock clock = new MutableClock(NOW);
        final ScriptedTransport transport = ScriptedTransport.responding(request -> {
            throw new UncheckedIOException(new InterruptedIOException("timeout"));
        });

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            final Throwable first = catchThrowable(auth::authentication);
            final Throwable second = catchThrowable(auth::authentication);

            assertThat(first).isInstanceOf(UncheckedIOException.class);
            assertThat(second)
                    .isInstanceOf(GitHubInstallationTokenUnavailableException.class)
                    .hasCause(first);
            assertThat(transport.calls()).isEqualTo(1);
        }
    }

    @Test
    void failureBackoff_shouldReject_whenArgumentsInvalid() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.failureBackoff(null, Duration.ofSeconds(1))))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.failureBackoff(Duration.ofSeconds(1), null)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.failureBackoff(Duration.ZERO, Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(
                            catchThrowable(() -> builder.failureBackoff(Duration.ofSeconds(-1), Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(
                            catchThrowable(() -> builder.failureBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void isRenewableAfterRejection_shouldRenewAtMostOnce_whenEveryCallGets401() {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger fetches = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(request -> request.method() == HttpMethod.POST
                ? issued("ghs_" + fetches.incrementAndGet(), clock.instant().plus(HOUR))
                : unauthorized());

        try (GitHubInstallationAuthenticationProvider auth =
                        auth(clock, transport.transport()).build();
                GitHubClient client = clientWith(auth, transport.transport())) {
            final HttpCall userRequest = HttpCall.get("/user");

            for (int call = 0; call < 20; call++) {
                final Throwable thrown = catchThrowable(() -> client.execute(userRequest));

                assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
            }
        }

        assertThat(fetches.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void isRenewableAfterRejection_shouldRenewAgain_whenTheRejectedTokenIsOlderThanTheInterval() throws Exception {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger fetches = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(request -> request.method() == HttpMethod.POST
                ? issued("ghs_" + fetches.incrementAndGet(), clock.instant().plus(HOUR))
                : usesToken(request, "ghs_1") ? unauthorized() : ok());

        try (GitHubInstallationAuthenticationProvider auth =
                        auth(clock, transport.transport()).build();
                GitHubClient client = clientWith(auth, transport.transport())) {
            auth.authentication().orElseThrow().value();
            clock.advance(Duration.ofSeconds(10));

            final HttpResponse response = client.execute(HttpCall.get("/user"));

            assertThat(response.statusCode()).isEqualTo(200);
        }

        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void isRenewableAfterRejection_shouldRenewOnce_whenSixteenThreadsGet401ForTheSameOldToken() throws Exception {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger fetches = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(request -> request.method() == HttpMethod.POST
                ? issued("ghs_" + fetches.incrementAndGet(), clock.instant().plus(HOUR))
                : usesToken(request, "ghs_1") ? unauthorized() : ok());

        try (GitHubInstallationAuthenticationProvider auth =
                        auth(clock, transport.transport()).build();
                GitHubClient client = clientWith(auth, transport.transport())) {
            auth.authentication().orElseThrow().value();
            clock.advance(Duration.ofMinutes(1));
            final HttpCall userRequest = HttpCall.get("/user");
            final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                final CountDownLatch start = new CountDownLatch(1);
                final List<Future<Throwable>> futures = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return catchThrowable(() -> client.execute(userRequest));
                    }));
                }
                start.countDown();
                for (final Future<Throwable> future : futures) {
                    assertThat(future.get(30, TimeUnit.SECONDS)).isNull();
                }
            } finally {
                pool.shutdownNow();
            }
        }

        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void isRenewableAfterRejection_shouldKeepTheCachedToken_whenTheRejectedOneIsNotTheCachedToken() {
        final MutableClock clock = new MutableClock(NOW);
        final AtomicInteger fetches = new AtomicInteger();
        final ScriptedTransport transport = ScriptedTransport.responding(request ->
                issued("ghs_" + fetches.incrementAndGet(), clock.instant().plus(HOUR)));

        try (GitHubInstallationAuthenticationProvider auth =
                auth(clock, transport.transport()).build()) {
            final boolean beforeAnyFetch = auth.isRenewableAfterRejection(Authentication.ofBearer("ghs_never_issued"));
            final String cached = auth.authentication().orElseThrow().value();
            clock.advance(Duration.ofMinutes(1));
            final boolean other = auth.isRenewableAfterRejection(Authentication.ofBearer("ghs_replaced_earlier"));
            final String afterOther = tokenOf(auth);

            assertSoftly(softly -> {
                softly.assertThat(beforeAnyFetch).isTrue();
                softly.assertThat(other).isTrue();
                softly.assertThat(afterOther).isEqualTo(cached);
                softly.assertThat(fetches.get()).isEqualTo(1);
            });
        }
    }

    @Test
    void minRenewInterval_shouldReject_whenValueNullOrNegative() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.minRenewInterval(null)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.minRenewInterval(Duration.ofSeconds(-1))))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    private static GitHubClient clientWith(
            final GitHubInstallationAuthenticationProvider auth, final HttpTransport transport) {
        return TestGitHubClients.raw(transport, TestClients.configuration(TestClients.GITHUB_BASE), auth);
    }

    private static boolean usesToken(final HttpRequest request, final String token) {
        return request.headers()
                .get(AUTHORIZATION)
                .filter(value -> value.endsWith(token))
                .isPresent();
    }

    private static HttpResponse ok() {
        return new HttpResponse(
                200,
                HttpHeaders.of(CONTENT_TYPE, "application/json"),
                HttpBody.of("{}".getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse unauthorized() {
        return new HttpResponse(
                401,
                HttpHeaders.of(CONTENT_TYPE, "application/json"),
                HttpBody.of("{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8)));
    }

    private static int uniqueSuffix() {
        return UNIQUE.incrementAndGet();
    }

    private static AuthFactory auth(final Clock clock, final HttpTransport transport) {
        return new AuthFactory(clock, transport);
    }

    private static final class AuthFactory {
        private final Clock clock;
        private final HttpTransport transport;
        private final GitHubInstallationAuthConfiguration.Builder configuration =
                GitHubInstallationAuthConfiguration.builder()
                        .appId("Iv1.test")
                        .privateKeyPem(AppKeys.pkcs1Pem())
                        .installationId(42);

        private AuthFactory(final Clock clock, final HttpTransport transport) {
            this.clock = clock;
            this.transport = transport;
        }

        AuthFactory refreshMargin(final Duration refreshMargin) {
            configuration.refreshMargin(refreshMargin);
            return this;
        }

        AuthFactory failureBackoff(final Duration initial, final Duration max) {
            configuration.failureBackoff(initial, max);
            return this;
        }

        GitHubInstallationAuthenticationProvider build() {
            return TestInstallationAuths.create(transport, configuration.build(), clock);
        }
    }

    private static HttpResponse issued(final String token, final Instant expiresAt) {
        final byte[] body =
                ("{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}").getBytes(StandardCharsets.UTF_8);
        return new HttpResponse(201, HttpHeaders.of(CONTENT_TYPE, "application/json"), HttpBody.of(body));
    }

    private static String tokenOf(final GitHubInstallationAuthenticationProvider auth) {
        return auth.authentication().orElseThrow().value();
    }

    private static HttpResponse failure(final int status) {
        return new HttpResponse(
                status,
                HttpHeaders.of(CONTENT_TYPE, "application/json"),
                HttpBody.of("{\"message\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> callTokenConcurrently(
            final GitHubInstallationAuthenticationProvider auth, final int threads) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return tokenOf(auth);
                }));
            }
            start.countDown();
            final List<String> tokens = new ArrayList<>();
            for (final Future<String> future : futures) {
                tokens.add(future.get(30, TimeUnit.SECONDS));
            }
            return tokens;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Throwable> callTokenConcurrentlyExpectingFailure(
            final GitHubInstallationAuthenticationProvider auth, final int threads) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<Throwable>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return catchThrowable(auth::authentication);
                }));
            }
            start.countDown();
            final List<Throwable> failures = new ArrayList<>();
            for (final Future<Throwable> future : futures) {
                failures.add(future.get(30, TimeUnit.SECONDS));
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }
}
