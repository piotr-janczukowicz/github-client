package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitInfo;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.RateLimitEvent;
import io.github.piotr_janczukowicz.http.transport.RateLimitGateHeaders;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@UnitTest
@Isolated
class GitHubClientRateLimitGateTest {

    private static final HttpCall GET_USER = HttpCall.get("/user");
    private static final HttpCall SEARCH_ISSUES = HttpCall.get("/search/issues");
    private static final HttpCall UPLOAD_SARIF = HttpCall.of(HttpMethod.POST, "/repos/o/r/code-scanning/sarifs");
    private static final int FORBIDDEN = HttpStatus.FORBIDDEN.code();

    private HttpTransport delegate;

    @BeforeEach
    void setUpDelegate() {
        delegate = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldWaitForSecondaryLimit_whenAnotherCallWasRejected() throws Exception {
        final AtomicReference<RateLimitStack> stackRef = new AtomicReference<>();
        final List<Integer> sleepsSeenByDelegate = new ArrayList<>();
        final Deque<HttpResponse> responses =
                new ArrayDeque<>(List.of(secondaryAfter60(), secondaryAfter60(), RateLimitResponses.ok()));
        when(delegate.execute(any())).thenAnswer(invocation -> {
            sleepsSeenByDelegate.add(stackRef.get().sleeps().size());
            return responses.poll();
        });

        try (final RateLimitStack stack = new RateLimitStack(delegate, builder -> builder.maxRateLimitAttempts(2))) {
            final GitHubClient client = stack.client();
            stackRef.set(stack);
            final Throwable first = catchThrowable(() -> client.execute(GET_USER));
            final HttpResponse second = get(stack);

            assertThat(first).isInstanceOf(GitHubRateLimitException.class);
            assertThat(second.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(60));
            assertThat(sleepsSeenByDelegate).containsExactly(0, 1, 2);
        }
    }

    @Test
    void execute_shouldKeepSearchLimitApartFromCore_whenSearchQuotaIsSpent() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(30));
            when(delegate.execute(any()))
                    .thenReturn(
                            RateLimitResponses.primary(HttpStatus.FORBIDDEN, stack.start(), reset, "search"),
                            RateLimitResponses.ok());

            final Throwable search = catchThrowable(() -> client.execute(SEARCH_ISSUES));
            final HttpResponse core = stack.client().execute(HttpCall.get("/repos/o/r"));
            final Throwable searchAgain = catchThrowable(() -> client.execute(SEARCH_ISSUES));

            assertThat(search).isInstanceOf(GitHubRateLimitException.class);
            assertThat(core.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(searchAgain).isInstanceOf(GitHubRateLimitException.class);
            final GitHubRateLimitException blocked = (GitHubRateLimitException) searchAgain;
            assertSoftly(softly -> {
                softly.assertThat(blocked.headers().get(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(blocked.kind()).contains(GitHubRateLimitInfo.Kind.PRIMARY);
                softly.assertThat(blocked.retryAt().orElseThrow())
                        .isBetween(reset.minusSeconds(5), reset.plusSeconds(10));
                softly.assertThat(stack.sleeps()).isEmpty();
            });
            verify(delegate, times(2)).execute(any());
            assertThat(stack.events())
                    .extracting(RateLimitEvent::type)
                    .containsExactly(RateLimitEvent.Type.GIVE_UP_WAIT_TOO_LONG, RateLimitEvent.Type.GATE_FAIL_FAST);
            assertThat(stack.events().get(1).bucket()).isEqualTo("search");
        }
    }

    @Test
    void execute_shouldReportQuotaOnce_whenSecondCallIsRefusedByTheGate() throws Exception {
        final List<GitHubRateLimitSnapshot> snapshots = new ArrayList<>();

        try (final RateLimitStack stack = RateLimitStack.listening(delegate, snapshots::add)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(30));
            when(delegate.execute(any()))
                    .thenReturn(RateLimitResponses.primary(HttpStatus.FORBIDDEN, stack.start(), reset, "search"));

            final Throwable rejected = catchThrowable(() -> client.execute(SEARCH_ISSUES));
            final Throwable replayed = catchThrowable(() -> client.execute(SEARCH_ISSUES));

            assertSoftly(softly -> {
                softly.assertThat(rejected).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) rejected).isClientSideBlock())
                        .isFalse();
                softly.assertThat(replayed).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) replayed).isClientSideBlock())
                        .isTrue();
                softly.assertThat(snapshots).singleElement().satisfies(snapshot -> {
                    assertThat(snapshot.resource()).isEqualTo("search");
                    assertThat(snapshot.remaining()).isZero();
                });
            });
            verify(delegate, times(1)).execute(any());
        }
    }

    @Test
    void execute_shouldNotHoldBackOtherCredential_whenSecondaryLimitHitsOne() throws Exception {
        when(delegate.execute(any()))
                .thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN), RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate, builder -> builder.maxRateLimitAttempts(1))) {
            final GitHubClient client = stack.client();
            final Throwable first = catchThrowable(() -> client.execute(GET_USER));
            stack.use(AuthenticationProviders.bearer("another-token"));
            final HttpResponse other = stack.client().execute(HttpCall.get("/user"));

            assertThat(first).isInstanceOf(GitHubRateLimitException.class);
            assertThat(other.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).isEmpty();
        }
    }

    @Test
    void execute_shouldNotHoldBackAnonymousCall_whenTokenCallWasLimited() throws Exception {
        when(delegate.execute(any()))
                .thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN), RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate, builder -> builder.maxRateLimitAttempts(1))) {
            final GitHubClient client = stack.client();
            final Throwable limited = catchThrowable(() -> client.execute(GET_USER));
            stack.use(AuthenticationProviders.none());
            final HttpResponse anonymous = stack.client().execute(HttpCall.get("/user"));

            assertThat(limited).isInstanceOf(GitHubRateLimitException.class);
            assertThat(anonymous.statusCode()).isEqualTo(HttpStatus.OK.code());
        }
    }

    @Test
    void execute_shouldNotHoldBackTokenCall_whenAnonymousCallWasLimited() throws Exception {
        when(delegate.execute(any()))
                .thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN), RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate, builder -> builder.maxRateLimitAttempts(1))) {
            final GitHubClient client = stack.client();
            stack.use(AuthenticationProviders.none());
            final Throwable limited = catchThrowable(() -> client.execute(GET_USER));
            stack.use(TestClients.auth());
            final HttpResponse token = get(stack);

            assertThat(limited).isInstanceOf(GitHubRateLimitException.class);
            assertThat(token.statusCode()).isEqualTo(HttpStatus.OK.code());
        }
    }

    @Test
    void execute_shouldFailFastWithoutSending_whenBlockOutlastsMaxRateLimitWait() throws Exception {
        final HttpResponse limited = response(
                FORBIDDEN,
                RateLimitResponses.SECONDARY_BODY,
                RETRY_AFTER,
                "3600",
                RATE_LIMIT_REMAINING,
                "4000",
                RATE_LIMIT_RESOURCE,
                "core");
        when(delegate.execute(any())).thenReturn(limited);

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Throwable first = catchThrowable(() -> client.execute(GET_USER));
            final Throwable second = catchThrowable(() -> client.execute(GET_USER));

            assertThat(first).isInstanceOf(GitHubRateLimitException.class);
            assertThat(second).isInstanceOf(GitHubRateLimitException.class);
            final GitHubRateLimitException blocked = (GitHubRateLimitException) second;
            assertSoftly(softly -> {
                softly.assertThat(blocked.kind()).contains(GitHubRateLimitInfo.Kind.SECONDARY);
                softly.assertThat(blocked.headers().get(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                        .contains(RateLimitGateHeaders.BLOCKED);
                softly.assertThat(blocked.rateLimit().orElseThrow().retryAfter())
                        .isBetween(Duration.ofSeconds(3590), Duration.ofSeconds(3600));
            });
        }
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldFailFastWithoutSending_whenBlockOutlastsTotalDeadline() throws Exception {
        when(delegate.execute(any())).thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN));

        try (final RateLimitStack stack = new RateLimitStack(
                delegate, builder -> builder.maxRateLimitAttempts(1).totalDeadline(Duration.ofSeconds(10)))) {
            final GitHubClient client = stack.client();
            catchThrowable(() -> client.execute(GET_USER));
            final Throwable second = catchThrowable(() -> client.execute(GET_USER));

            assertThat(second).isInstanceOf(GitHubRateLimitException.class);
            assertThat(((GitHubRateLimitException) second).headers().get(HttpHeaderNames.X_CLIENT_RATE_LIMIT_GATE))
                    .contains(RateLimitGateHeaders.BLOCKED);
        }
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldSendImmediately_whenCoordinationIsOff() throws Exception {
        when(delegate.execute(any()))
                .thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN), RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(
                delegate, builder -> builder.maxRateLimitAttempts(1).coordinateRateLimits(false))) {
            final GitHubClient client = stack.client();
            catchThrowable(() -> client.execute(GET_USER));
            final HttpResponse second = get(stack);

            assertThat(second.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).isEmpty();
            assertThat(stack.gateSize()).isZero();
        }
    }

    @Test
    void execute_shouldSpreadWaiters_whenInjectedRandomDrawsDifferentJitter() throws Exception {
        when(delegate.execute(any()))
                .thenReturn(secondaryAfter60(), secondaryAfter60(), RateLimitResponses.ok(), RateLimitResponses.ok());
        final RandomGenerator random = randomDrawing(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(8));
        final List<Duration> sleeps = new ArrayList<>();

        try (final RateLimitStack stack = new RateLimitStack(
                delegate,
                builder -> builder.maxRateLimitAttempts(2).rateLimitJitter(Duration.ofSeconds(10)),
                random,
                sleeps::add)) {
            final GitHubClient client = stack.client();
            catchThrowable(() -> client.execute(GET_USER));
            get(stack);
            get(stack);
        }

        assertThat(sleeps)
                .containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(63), Duration.ofSeconds(68))
                .allSatisfy(sleep -> assertThat(sleep)
                        .isGreaterThanOrEqualTo(Duration.ofSeconds(60))
                        .isLessThan(Duration.ofSeconds(70)));
    }

    @Test
    void execute_shouldNotReachDelegate_whenThreadsWaitForABlock() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        when(delegate.execute(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return calls.get() <= 2 ? secondaryAfter60() : RateLimitResponses.ok();
        });
        final CountDownLatch bothWaiting = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<RateLimitStack> stackRef = new AtomicReference<>();
        final AtomicBoolean racing = new AtomicBoolean();
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        try (final RateLimitStack stack =
                new RateLimitStack(delegate, builder -> builder.maxRateLimitAttempts(2), null, duration -> {
                    if (racing.get()) {
                        bothWaiting.countDown();
                        release.await(10, TimeUnit.SECONDS);
                    }
                    stackRef.get().clock().advance(duration);
                })) {
            final GitHubClient client = stack.client();
            stackRef.set(stack);
            catchThrowable(() -> client.execute(GET_USER));
            racing.set(true);
            final Future<HttpResponse> a = executor.submit(() -> get(stack));
            final Future<HttpResponse> b = executor.submit(() -> get(stack));

            final boolean waiting = bothWaiting.await(10, TimeUnit.SECONDS);
            final int callsDuringBlock = calls.get();
            release.countDown();
            final int statusA = a.get(10, TimeUnit.SECONDS).statusCode();
            final int statusB = b.get(10, TimeUnit.SECONDS).statusCode();

            assertSoftly(softly -> {
                softly.assertThat(waiting).isTrue();
                softly.assertThat(callsDuringBlock).isEqualTo(2);
                softly.assertThat(statusA).isEqualTo(HttpStatus.OK.code());
                softly.assertThat(statusB).isEqualTo(HttpStatus.OK.code());
                softly.assertThat(calls.get()).isEqualTo(4);
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void execute_shouldFailFastWithClientSideBlock_whenSarifUploadWasLimited() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any()))
                    .thenReturn(
                            RateLimitResponses.primary(
                                    HttpStatus.FORBIDDEN, stack.start(), reset, "code_scanning_upload"),
                            RateLimitResponses.ok());

            final Throwable rejected = catchThrowable(() -> client.execute(UPLOAD_SARIF));
            final Throwable replayed = catchThrowable(() -> client.execute(UPLOAD_SARIF));
            verify(delegate, times(1)).execute(any());
            final HttpResponse core = get(stack);

            assertSoftly(softly -> {
                softly.assertThat(rejected).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(replayed).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) replayed).isClientSideBlock())
                        .isTrue();
                softly.assertThat(core.statusCode()).isEqualTo(HttpStatus.OK.code());
            });
            verify(delegate, times(2)).execute(any());
        }
    }

    @Test
    void execute_shouldFailFastWithClientSideBlock_whenSarifUploadWasLimitedWithoutResourceHeader() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any()))
                    .thenReturn(
                            RateLimitResponses.primaryWithoutResource(HttpStatus.FORBIDDEN, stack.start(), reset),
                            RateLimitResponses.ok());

            final Throwable rejected = catchThrowable(() -> client.execute(UPLOAD_SARIF));
            final Throwable replayed = catchThrowable(() -> client.execute(UPLOAD_SARIF));
            verify(delegate, times(1)).execute(any());
            final HttpResponse core = get(stack);

            assertSoftly(softly -> {
                softly.assertThat(rejected).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(replayed).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) replayed).isClientSideBlock())
                        .isTrue();
                softly.assertThat(core.statusCode()).isEqualTo(HttpStatus.OK.code());
            });
            verify(delegate, times(2)).execute(any());
        }
    }

    @Test
    void execute_shouldHoldBackSarifUpload_whenResourceHeaderNamesAnUnknownResource() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any()))
                    .thenReturn(
                            RateLimitResponses.primary(HttpStatus.FORBIDDEN, stack.start(), reset, "new_resource"),
                            RateLimitResponses.ok());

            catchThrowable(() -> client.execute(UPLOAD_SARIF));
            final Throwable replayed = catchThrowable(() -> client.execute(UPLOAD_SARIF));

            assertThat(replayed).isInstanceOf(GitHubRateLimitException.class);
            assertThat(((GitHubRateLimitException) replayed).isClientSideBlock())
                    .isTrue();
            verify(delegate, times(1)).execute(any());
        }
    }

    @Test
    void execute_shouldSendSarifUploadButHoldBackSearch_whenResourceHeaderNamesSearch() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any()))
                    .thenReturn(
                            RateLimitResponses.primary(HttpStatus.FORBIDDEN, stack.start(), reset, "search"),
                            RateLimitResponses.ok());

            catchThrowable(() -> client.execute(UPLOAD_SARIF));
            final HttpResponse uploaded = upload(stack);
            final Throwable search = catchThrowable(() -> client.execute(SEARCH_ISSUES));

            assertThat(uploaded.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(search).isInstanceOf(GitHubRateLimitException.class);
            assertThat(((GitHubRateLimitException) search).isClientSideBlock()).isTrue();
            verify(delegate, times(2)).execute(any());
        }
    }

    @Test
    void execute_shouldWaitForResetWithoutSending_whenSuccessSpentTheQuota() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final Instant reset = stack.start().plus(Duration.ofMinutes(2));
            final List<Instant> sentAt = new ArrayList<>();
            when(delegate.execute(any())).thenAnswer(invocation -> {
                sentAt.add(stack.clock().instant());
                return sentAt.size() == 1
                        ? RateLimitResponses.spent(HttpStatus.OK, stack.start(), reset)
                        : RateLimitResponses.ok();
            });

            final HttpResponse first = get(stack);
            final HttpResponse second = get(stack);

            assertSoftly(softly -> {
                softly.assertThat(first.statusCode()).isEqualTo(HttpStatus.OK.code());
                softly.assertThat(second.statusCode()).isEqualTo(HttpStatus.OK.code());
                softly.assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(121));
                softly.assertThat(sentAt)
                        .containsExactly(stack.start(), stack.start().plusSeconds(121));
            });
        }
    }

    @Test
    void execute_shouldFailFastWithClientSideBlock_whenSuccessSpentTheQuotaUntilALaterReset() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any())).thenReturn(RateLimitResponses.spent(HttpStatus.OK, stack.start(), reset));

            final HttpResponse first = get(stack);
            final Throwable second = catchThrowable(() -> client.execute(GET_USER));

            assertSoftly(softly -> {
                softly.assertThat(first.statusCode()).isEqualTo(HttpStatus.OK.code());
                softly.assertThat(second).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) second).isClientSideBlock())
                        .isTrue();
                softly.assertThat(stack.sleeps()).isEmpty();
            });
            verify(delegate, times(1)).execute(any());
        }
    }

    @Test
    void execute_shouldSendNextCall_whenSuccessLeavesQuotaRemaining() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any()))
                    .thenReturn(
                            response(
                                    HttpStatus.OK.code(),
                                    null,
                                    RATE_LIMIT_REMAINING,
                                    "4999",
                                    RATE_LIMIT_RESET,
                                    RateLimitResponses.epoch(reset)),
                            RateLimitResponses.ok());

            get(stack);
            final HttpResponse second = get(stack);

            assertThat(second.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).isEmpty();
            verify(delegate, times(2)).execute(any());
        }
    }

    @Test
    void execute_shouldSendNextCall_whenSuccessHasNoRemainingHeader() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any()))
                    .thenReturn(
                            response(HttpStatus.OK.code(), null, RATE_LIMIT_RESET, RateLimitResponses.epoch(reset)),
                            RateLimitResponses.ok());

            get(stack);
            final HttpResponse second = get(stack);

            assertThat(second.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).isEmpty();
            verify(delegate, times(2)).execute(any());
        }
    }

    @Test
    void execute_shouldFailFastWithClientSideBlock_whenNotModifiedSpentTheQuota() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Instant reset = stack.start().plus(Duration.ofMinutes(10));
            when(delegate.execute(any()))
                    .thenReturn(RateLimitResponses.spent(HttpStatus.NOT_MODIFIED, stack.start(), reset));

            final HttpResponse first =
                    stack.client().execute(HttpCall.get("/user"), Set.of(HttpStatus.NOT_MODIFIED.code()));
            final Throwable second = catchThrowable(() -> client.execute(GET_USER));

            assertSoftly(softly -> {
                softly.assertThat(first.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.code());
                softly.assertThat(second).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) second).isClientSideBlock())
                        .isTrue();
            });
            verify(delegate, times(1)).execute(any());
        }
    }

    private static RandomGenerator randomDrawing(final Duration... jitters) {
        final Deque<Long> draws = new ArrayDeque<>();
        for (final Duration jitter : jitters) {
            draws.add(jitter.toNanos());
        }
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public long nextLong(final long bound) {
                final Long draw = draws.poll();
                return draw == null ? 0L : draw;
            }
        };
    }

    private static HttpResponse secondaryAfter60() {
        return response(
                FORBIDDEN,
                RateLimitResponses.SECONDARY_BODY,
                RETRY_AFTER,
                "60",
                RATE_LIMIT_REMAINING,
                "4000",
                RATE_LIMIT_RESOURCE,
                "core");
    }

    private static HttpResponse get(final RateLimitStack stack) throws IOException {
        return stack.client().execute(HttpCall.get("/user"));
    }

    private static HttpResponse upload(final RateLimitStack stack) throws IOException {
        return stack.client().execute(HttpCall.of(HttpMethod.POST, "/repos/o/r/code-scanning/sarifs"));
    }
}
