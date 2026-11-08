package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.github.client.RateLimitResponses.PERMISSION_BODY;
import static io.github.piotr_janczukowicz.github.client.RateLimitResponses.SECONDARY_BODY;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.test.TestClients.GITHUB_BASE;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.exception.GitHubForbiddenException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitDetector;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitInfo;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.RateLimitEvent;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.test.http.TestRetryingTransports;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.DateTimeUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@UnitTest
@Isolated
class GitHubClientRateLimitRetryTest {

    private static final int FORBIDDEN = HttpStatus.FORBIDDEN.code();
    private static final int TOO_MANY = HttpStatus.TOO_MANY_REQUESTS.code();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    private static final HttpCall GET_USER = HttpCall.get("/user");
    private static final HttpCall HEAD_REPO = HttpCall.of(HttpMethod.HEAD, "/repos/o/r");

    private final Deque<AutoCloseable> opened = new ArrayDeque<>();
    private HttpTransport delegate;

    @AfterEach
    void closeClients() throws Exception {
        while (!opened.isEmpty()) {
            opened.pop().close();
        }
    }

    @BeforeEach
    void setUpDelegate() {
        delegate = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldRetryForbiddenSecondaryLimit_whenClientWrapsInjectedTransport() throws Exception {
        final HttpResponse limited = RateLimitResponses.secondary(HttpStatus.FORBIDDEN);
        when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());
        final HttpClientConfiguration configuration =
                HttpClientConfiguration.builder(GITHUB_BASE).build();

        final HttpResponse response;
        try (final GitHubClient client = TestGitHubClients.raw(
                TestGitHubClients.retrying(delegate, withoutWaits().build()), configuration, TestClients.auth())) {
            response = client.execute(HttpCall.get("/user"));
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(2)).execute(any());
    }

    @Test
    void execute_shouldRetry_whenForbiddenWithZeroRemainingAndPastReset() throws Exception {
        final HttpResponse limited = response(FORBIDDEN, null, RATE_LIMIT_REMAINING, "0", RATE_LIMIT_RESET, "0");
        when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());
        final RetryConfiguration retry = RetryConfiguration.builder()
                .rateLimitDetector(GitHubRateLimitDetector.INSTANCE)
                .rateLimitJitter(Duration.ZERO)
                .clockSkewMargin(Duration.ZERO)
                .build();

        final HttpResponse response = clientWithRetry(retry).execute(GET_USER);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(2)).execute(any());
    }

    @Test
    void execute_shouldThrowRateLimit_whenPrimaryResetExceedsMaxWait() throws Exception {
        final Instant resetAt = CLOCK.instant().plus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS);
        when(delegate.execute(any()))
                .thenReturn(response(
                        FORBIDDEN,
                        null,
                        RATE_LIMIT_REMAINING,
                        "0",
                        RATE_LIMIT_RESET,
                        RateLimitResponses.epoch(resetAt)));
        final RetryConfiguration retry = detectingRetry(Duration.ofSeconds(5));
        final List<Duration> sleeps = new ArrayList<>();

        final GitHubClient client = clientOnClock(retry, CLOCK, sleeps);

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertThat(thrown)
                .isInstanceOf(GitHubRateLimitException.class)
                .extracting(ex -> ((GitHubRateLimitException) ex)
                        .rateLimit()
                        .orElseThrow()
                        .resetAt())
                .isEqualTo(resetAt);
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldThrowRateLimitWithoutWaiting_whenPrimaryResetIsFortyMinutesAwayUnderDefaults() throws Exception {
        final Instant resetAt = CLOCK.instant().plus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS);
        when(delegate.execute(any()))
                .thenReturn(RateLimitResponses.primary(HttpStatus.FORBIDDEN, CLOCK.instant(), resetAt, "core"));
        final RetryConfiguration retry = RetryConfiguration.builder()
                .rateLimitJitter(Duration.ZERO)
                .build()
                .withRateLimitDetector(GitHubRateLimitDetector.INSTANCE);
        final List<Duration> sleeps = new ArrayList<>();

        final GitHubClient client = clientOnClock(retry, CLOCK, sleeps);

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
            softly.assertThat(sleeps).isEmpty();
        });
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void lastRateLimit_shouldStampWithInjectedClock_whenClientHasClock() throws Exception {
        when(delegate.execute(any()))
                .thenReturn(response(
                        HttpStatus.OK.code(),
                        null,
                        RATE_LIMIT_LIMIT,
                        "5000",
                        RATE_LIMIT_REMAINING,
                        "4990",
                        RATE_LIMIT_USED,
                        "10",
                        RATE_LIMIT_RESET,
                        "1767225600",
                        RATE_LIMIT_RESOURCE,
                        "core"));

        final GitHubRateLimitSnapshot snapshot;
        try (final GitHubClient client = DefaultGitHubClient.builder(
                        delegate, TestClients.configuration(GITHUB_BASE), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .clock(CLOCK)
                .build()) {
            client.execute(HttpCall.get("/user"));
            snapshot = client.lastRateLimit("core").orElseThrow();
        }

        assertThat(snapshot.observedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void execute_shouldRetryUntilBudgetIsUsed_whenPrimaryLimitHasNoReset() throws Exception {
        when(delegate.execute(any())).thenReturn(response(FORBIDDEN, null, RATE_LIMIT_REMAINING, "0"));

        final GitHubClient client = clientWithRetry(detectingRetry(Duration.ZERO));

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
        verify(delegate, times(4)).execute(any());
    }

    @Test
    void execute_shouldRetryUntilBudgetIsUsed_whenForbiddenSecondaryMessageHasNoWait() throws Exception {
        when(delegate.execute(any())).thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN));

        final GitHubClient client = clientWithRetry(detectingRetry(Duration.ZERO));

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
        verify(delegate, times(4)).execute(any());
    }

    @Test
    void execute_shouldThrowAuthorization_whenOrdinaryForbidden() throws Exception {
        when(delegate.execute(any())).thenReturn(RateLimitResponses.status(HttpStatus.FORBIDDEN));

        final GitHubClient client = clientWithRetry(detectingRetry(Duration.ofSeconds(5)));

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertThat(thrown).isInstanceOf(GitHubForbiddenException.class);
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldThrowForbidden_whenRetryAfterComesWithoutRateLimitHeaders() throws Exception {
        when(delegate.execute(any())).thenReturn(response(FORBIDDEN, null, RETRY_AFTER, "30"));

        final GitHubClient client = clientWithRetry(detectingRetry(Duration.ofSeconds(5)));

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertThat(thrown).isInstanceOf(GitHubForbiddenException.class);
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldApplyGitHubDetector_whenConfiguredRetryHasNoDetector() throws Exception {
        final HttpResponse limited = RateLimitResponses.secondary(HttpStatus.FORBIDDEN);
        when(delegate.execute(any())).thenReturn(limited, limited, RateLimitResponses.ok());
        final RetryConfiguration retry = withoutWaits().maxRateLimitAttempts(3).build();

        final HttpResponse response = configuredClient(retry).execute(GET_USER);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(3)).execute(any());
    }

    @Test
    void execute_shouldApplyGitHubDetector_whenRetryHasExplicitDetector() throws Exception {
        final HttpResponse limited = RateLimitResponses.secondary(HttpStatus.FORBIDDEN);
        when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());
        final RetryConfiguration retry = withoutWaits()
                .rateLimitDetector((response, clock) -> Optional.empty())
                .build();

        final HttpResponse response = configuredClient(retry).execute(GET_USER);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
        verify(delegate, times(2)).execute(any());
    }

    @Test
    void execute_shouldHonorConfiguredAttempts_whenRetrySet() throws Exception {
        when(delegate.execute(any())).thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN));
        final RetryConfiguration retry = withoutWaits().maxRateLimitAttempts(1).build();

        final GitHubClient client = configuredClient(retry);

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldNotThrowUnchecked_whenResetHeaderIsOutOfRange() throws Exception {
        final HttpResponse limited =
                response(FORBIDDEN, null, RATE_LIMIT_REMAINING, "0", RATE_LIMIT_RESET, "9223372036854775807");
        when(delegate.execute(any())).thenReturn(limited);
        final RetryConfiguration retry = RetryConfiguration.builder()
                .minRateLimitWait(Duration.ZERO)
                .maxRateLimitWait(Duration.ofSeconds(1))
                .rateLimitJitter(Duration.ZERO)
                .build();

        final GitHubClient client = configuredClient(retry);

        final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

        assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void execute_shouldWaitUntilReset_whenTooManyRequestsWithZeroRemaining() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final Instant reset = stack.start().plusSeconds(120);
            when(delegate.execute(any()))
                    .thenReturn(
                            RateLimitResponses.primary(HttpStatus.TOO_MANY_REQUESTS, stack.start(), reset, "core"),
                            RateLimitResponses.ok());

            final HttpResponse response = stack.client().execute(GET_USER);

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(121));
        }
    }

    @Test
    void execute_shouldThrowForbidden_whenPermissionErrorHasQuotaLeft() throws Exception {
        when(delegate.execute(any())).thenReturn(response(FORBIDDEN, PERMISSION_BODY, RATE_LIMIT_REMAINING, "4999"));

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

            assertThat(thrown).isInstanceOf(GitHubForbiddenException.class);
            assertThat(stack.sleeps()).isEmpty();
        }
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldWaitSixtySeconds_whenForbiddenWithSecondaryMessage() throws Exception {
        when(delegate.execute(any()))
                .thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN), RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final HttpResponse response = stack.client().execute(GET_USER);

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(60));
        }
    }

    @Test
    void execute_shouldWaitRetryAfter_whenTooManyRequestsWithQuotaLeft() throws Exception {
        final HttpResponse limited =
                response(TOO_MANY, null, RETRY_AFTER, "45", RATE_LIMIT_LIMIT, "5000", RATE_LIMIT_REMAINING, "100");
        when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            stack.client().execute(GET_USER);

            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(45));
        }
    }

    @Test
    void execute_shouldWaitUntilReset_whenSecondaryMessageHasZeroRemainingAndNoRetryAfter() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final HttpResponse limited = response(
                    FORBIDDEN,
                    SECONDARY_BODY,
                    DATE,
                    DateTimeUtils.toRfc1123(stack.start()),
                    RATE_LIMIT_REMAINING,
                    "0",
                    RATE_LIMIT_RESET,
                    RateLimitResponses.epoch(stack.start().plusSeconds(120)));
            when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());

            stack.client().execute(GET_USER);

            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(121));
        }
    }

    @Test
    void execute_shouldReportSecondary_whenSecondaryMessageResetDoesNotFit() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final HttpResponse limited = response(
                    FORBIDDEN,
                    SECONDARY_BODY,
                    DATE,
                    DateTimeUtils.toRfc1123(stack.start()),
                    RATE_LIMIT_REMAINING,
                    "0",
                    RATE_LIMIT_RESET,
                    RateLimitResponses.epoch(stack.start().plusSeconds(300)));
            when(delegate.execute(any())).thenReturn(limited);

            final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

            assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
            assertThat(((GitHubRateLimitException) thrown).kind()).contains(GitHubRateLimitInfo.Kind.SECONDARY);
            assertThat(stack.sleeps()).isEmpty();
        }
    }

    @Test
    void execute_shouldWaitRetryAfter_whenSecondaryHasZeroRemainingAndRetryAfter() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final HttpResponse limited = response(
                    FORBIDDEN,
                    SECONDARY_BODY,
                    DATE,
                    DateTimeUtils.toRfc1123(stack.start()),
                    RETRY_AFTER,
                    "30",
                    RATE_LIMIT_REMAINING,
                    "0",
                    RATE_LIMIT_RESET,
                    RateLimitResponses.epoch(stack.start().plusSeconds(300)));
            when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());

            stack.client().execute(GET_USER);

            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(30));
        }
    }

    @Test
    void execute_shouldDoubleWait_whenSecondaryLimitRepeats() throws Exception {
        final HttpResponse limited = RateLimitResponses.secondary(HttpStatus.FORBIDDEN);
        when(delegate.execute(any())).thenReturn(limited, limited, limited, RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final HttpResponse response = stack.client().execute(GET_USER);

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps())
                    .containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(240));
        }
    }

    @Test
    void execute_shouldThrowSecondaryWithDetails_whenEveryAttemptIsLimited() throws Exception {
        final HttpResponse limited =
                response(FORBIDDEN, SECONDARY_BODY, RATE_LIMIT_RESOURCE, "core", RATE_LIMIT_REMAINING, "4000");
        when(delegate.execute(any())).thenReturn(limited);

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();
            final Throwable thrown = catchThrowable(() -> client.execute(GET_USER));

            assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
            final GitHubRateLimitException exception = (GitHubRateLimitException) thrown;
            assertSoftly(softly -> {
                softly.assertThat(exception.kind()).contains(GitHubRateLimitInfo.Kind.SECONDARY);
                softly.assertThat(exception.resource()).contains("core");
                softly.assertThat(exception.retryAt()).isPresent();
                softly.assertThat(stack.sleeps()).hasSize(3);
            });
        }
        verify(delegate, times(4)).execute(any());
    }

    @ParameterizedTest
    @CsvSource({"POST,201", "PATCH,200", "DELETE,204"})
    void execute_shouldResendBody_whenWriteIsSecondaryLimited(final HttpMethod method, final int successStatus)
            throws Exception {
        final byte[] payload = {1, 2, 3};
        when(delegate.execute(any()))
                .thenReturn(RateLimitResponses.secondary(HttpStatus.FORBIDDEN), response(successStatus, null));
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final HttpResponse response = stack.client()
                    .execute(HttpCall.of(method, "/repos/o/r/issues").withBody(HttpBody.of(payload)));

            assertThat(response.statusCode()).isEqualTo(successStatus);
            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(60));
        }

        verify(delegate, times(2)).execute(sent.capture());
        assertThat(sent.getAllValues())
                .extracting(request -> request.mapBody(bytes -> bytes, () -> new byte[0]))
                .containsExactly(payload, payload);
    }

    @ParameterizedTest
    @CsvSource({"10,100,101", "200,100,200"})
    void execute_shouldWaitForLaterOfRetryAfterAndReset_whenPrimaryLimitHasBoth(
            final long retryAfter, final long resetIn, final long expectedSeconds) throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final HttpResponse limited = response(
                    FORBIDDEN,
                    null,
                    DATE,
                    DateTimeUtils.toRfc1123(stack.start()),
                    RETRY_AFTER,
                    Long.toString(retryAfter),
                    RATE_LIMIT_REMAINING,
                    "0",
                    RATE_LIMIT_RESET,
                    RateLimitResponses.epoch(stack.start().plusSeconds(resetIn)),
                    RATE_LIMIT_RESOURCE,
                    "core");
            when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());

            stack.client().execute(GET_USER);

            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(expectedSeconds));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void execute_shouldWaitSixtySeconds_whenSecondaryRetryAfterIsNotPositive(final String retryAfter) throws Exception {
        final HttpResponse limited = response(FORBIDDEN, SECONDARY_BODY, RETRY_AFTER, retryAfter);
        when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            stack.client().execute(GET_USER);

            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(60));
        }
    }

    @Test
    void execute_shouldRetryHead_whenForbiddenHasRetryAfterAndRateLimitHeaders() throws Exception {
        final HttpResponse limited =
                response(FORBIDDEN, null, RETRY_AFTER, "60", RATE_LIMIT_LIMIT, "5000", RATE_LIMIT_REMAINING, "10");
        when(delegate.execute(any())).thenReturn(limited, RateLimitResponses.ok());

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final HttpResponse response = stack.client().execute(HttpCall.of(HttpMethod.HEAD, "/repos/o/r"));

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            assertThat(stack.sleeps()).containsExactly(Duration.ofSeconds(60));
        }
    }

    @Test
    void execute_shouldThrowForbidden_whenHeadForbiddenHasOnlyRemainingQuota() throws Exception {
        when(delegate.execute(any())).thenReturn(response(FORBIDDEN, null, RATE_LIMIT_REMAINING, "10"));

        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final GitHubClient client = stack.client();

            final Throwable thrown = catchThrowable(() -> client.execute(HEAD_REPO));

            assertThat(thrown).isInstanceOf(GitHubForbiddenException.class);
        }
        verify(delegate, times(1)).execute(any());
    }

    @Test
    void execute_shouldReportGiveUp_whenPrimaryResetIsBeyondTheCap() throws Exception {
        try (final RateLimitStack stack = new RateLimitStack(delegate)) {
            final Instant reset = stack.start().plus(Duration.ofMinutes(40));
            when(delegate.execute(any()))
                    .thenReturn(RateLimitResponses.primary(HttpStatus.FORBIDDEN, stack.start(), reset, "core"));
            final GitHubClient client = stack.client();

            final Throwable thrown;
            try (final LogCaptor logs = LogCaptor.forClass(RetryingHttpTransport.class)) {
                thrown = catchThrowable(() -> client.execute(GET_USER));

                assertThat(logs.getInfoLogs())
                        .singleElement()
                        .asString()
                        .contains("not retrying after rate-limit attempt 1")
                        .contains("asks for a wait of 2401000 ms, more than the 300000 ms allowed");
            }

            assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
            assertThat(stack.events())
                    .singleElement()
                    .satisfies(event -> assertSoftly(softly -> {
                        softly.assertThat(event.type()).isEqualTo(RateLimitEvent.Type.GIVE_UP_WAIT_TOO_LONG);
                        softly.assertThat(event.delay())
                                .isEqualTo(Duration.ofMinutes(40).plusSeconds(1));
                        softly.assertThat(event.bucket()).isEqualTo("core");
                        softly.assertThat(event.statusCode()).isEqualTo(FORBIDDEN);
                    }));
        }
        verify(delegate, times(1)).execute(any());
    }

    private static RetryConfiguration.Builder withoutWaits() {
        return RetryConfiguration.builder().minRateLimitWait(Duration.ZERO).rateLimitJitter(Duration.ZERO);
    }

    private GitHubClient configuredClient(final RetryConfiguration retry) {
        final GitHubClient client = TestGitHubClients.raw(
                TestGitHubClients.retrying(delegate, retry),
                HttpClientConfiguration.builder(GITHUB_BASE).build(),
                TestClients.auth());
        opened.push(client);
        return client;
    }

    private static RetryConfiguration detectingRetry(final Duration maxRateLimitWait) {
        return RetryConfiguration.builder()
                .rateLimitDetector(GitHubRateLimitDetector.INSTANCE)
                .minRateLimitWait(Duration.ZERO)
                .maxRateLimitWait(maxRateLimitWait)
                .rateLimitJitter(Duration.ZERO)
                .build();
    }

    private GitHubClient clientWithRetry(final RetryConfiguration retry) {
        final RetryingHttpTransport retrying = new RetryingHttpTransport(delegate, retry);
        final GitHubClient client =
                TestGitHubClients.raw(retrying, TestClients.configuration(GITHUB_BASE), TestClients.auth());
        opened.push(retrying);
        opened.push(client);
        return client;
    }

    private GitHubClient clientOnClock(final RetryConfiguration retry, final Clock clock, final List<Duration> sleeps) {
        final RetryingHttpTransport retrying = TestRetryingTransports.create(delegate, retry, sleeps::add, clock);
        final GitHubClient client = DefaultGitHubClient.builder(
                        retrying, TestClients.configuration(GITHUB_BASE), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .clock(clock)
                .build();
        opened.push(retrying);
        opened.push(client);
        return client;
    }
}
