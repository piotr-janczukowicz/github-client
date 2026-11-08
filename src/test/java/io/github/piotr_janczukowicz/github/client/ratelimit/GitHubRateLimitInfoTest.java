package io.github.piotr_janczukowicz.github.client.ratelimit;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.DateTimeUtils;
import io.github.piotr_janczukowicz.util.FileUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubRateLimitInfoTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @ParameterizedTest
    @MethodSource("rateLimitedResponses")
    void from_shouldBePresent_whenRateLimited(final HttpResponse response) {
        final Optional<GitHubRateLimitInfo> limited = GitHubRateLimitInfo.from(response, FIXED_CLOCK);

        assertThat(limited).isPresent();
    }

    @ParameterizedTest
    @MethodSource("nonRateLimitedResponses")
    void from_shouldBeEmpty_whenNotRateLimited(final HttpResponse response) {
        final Optional<GitHubRateLimitInfo> limited = GitHubRateLimitInfo.from(response, FIXED_CLOCK);

        assertThat(limited).isEmpty();
    }

    @Test
    void from_shouldNotReadRateLimitHeaders_whenStatusIsOk() {
        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), Map.of(), null);

        final Optional<GitHubRateLimitInfo> limited = GitHubRateLimitInfo.from(response, FIXED_CLOCK);

        assertThat(limited).isEmpty();
    }

    @Test
    void from_shouldIgnoreUnparseableIntHeaders_whenPresent() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(
                        RATE_LIMIT_LIMIT,
                        List.of("many"),
                        RATE_LIMIT_REMAINING,
                        List.of("0"),
                        RATE_LIMIT_USED,
                        List.of("nope")),
                null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(info.limit()).isNull();
            softly.assertThat(info.used()).isNull();
            softly.assertThat(info.remaining()).isZero();
        });
    }

    @Test
    void from_shouldThrow_whenResponseIsNull() {
        final Throwable thrown = catchThrowable(() -> GitHubRateLimitInfo.from(null, FIXED_CLOCK));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("response");
    }

    @Test
    void from_shouldThrow_whenClockIsNull() {
        final HttpResponse response = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        final Throwable thrown = catchThrowable(() -> GitHubRateLimitInfo.from(response, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }

    @Test
    void from_shouldLeaveResetAtNull_whenHeaderUnparseable() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of("soon")),
                null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertThat(info.resetAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775807", "-9223372036854775808", "99999999999999999"})
    void from_shouldLeaveResetAtNull_whenHeaderOutOfInstantRange(final String reset) {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of(reset)),
                null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertThat(info.resetAt()).isNull();
    }

    @Test
    void from_shouldClassifySecondary_whenTooManyRequestsWithoutRemainingZero() {
        final HttpResponse response = new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertThat(info.kind()).isEqualTo(GitHubRateLimitInfo.Kind.SECONDARY);
    }

    @Test
    void from_shouldClassifyPrimary_whenTooManyRequestsAndRemainingZero() {
        final HttpResponse response =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RATE_LIMIT_REMAINING, List.of("0")), null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertThat(info.kind()).isEqualTo(GitHubRateLimitInfo.Kind.PRIMARY);
    }

    @Test
    void from_shouldClassifySecondary_whenForbiddenBodyMentionsSecondaryLimit() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(),
                HttpBody.of(FileUtils.readBytes(GitHubRateLimitInfoTest.class, "secondary-rate-limit.json")));

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertThat(info.kind()).isEqualTo(GitHubRateLimitInfo.Kind.SECONDARY);
    }

    @Test
    void from_shouldParseHeaders_whenPresent() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(
                        RATE_LIMIT_LIMIT,
                        List.of("5000"),
                        RATE_LIMIT_REMAINING,
                        List.of("0"),
                        RATE_LIMIT_USED,
                        List.of("5000"),
                        RATE_LIMIT_RESET,
                        List.of("10"),
                        RATE_LIMIT_RESOURCE,
                        List.of("core")),
                null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(info.limit()).isEqualTo(5000);
            softly.assertThat(info.remaining()).isZero();
            softly.assertThat(info.used()).isEqualTo(5000);
            softly.assertThat(info.resetAt()).isEqualTo(Instant.ofEpochSecond(10));
            softly.assertThat(info.resource()).isEqualTo("core");
            softly.assertThat(info.kind()).isEqualTo(GitHubRateLimitInfo.Kind.PRIMARY);
        });
    }

    @Test
    void from_shouldParseRetryAfter_whenHeaderIsHttpDate() {
        final Instant until = Instant.ofEpochSecond(2);
        final String retryAfter =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(until, ZoneOffset.UTC));
        final HttpResponse response =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of(retryAfter)), null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertThat(info.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void from_shouldLeaveRetryAfterNull_whenHeaderUnparseable() {
        final HttpResponse response =
                HttpResponses.of(HttpStatus.TOO_MANY_REQUESTS.code(), Map.of(RETRY_AFTER, List.of("invalid")), null);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response, FIXED_CLOCK).orElseThrow();

        assertThat(info.retryAfter()).isNull();
    }

    private static Stream<HttpResponse> rateLimitedResponses() {
        return Stream.of(
                new HttpResponse(HttpStatus.TOO_MANY_REQUESTS.code(), HttpHeaders.empty(), null),
                HttpResponses.of(HttpStatus.FORBIDDEN.code(), Map.of(RATE_LIMIT_REMAINING, List.of("0")), null),
                HttpResponses.of(
                        HttpStatus.FORBIDDEN.code(),
                        Map.of(RETRY_AFTER, List.of("1"), RATE_LIMIT_REMAINING, List.of("4000")),
                        null),
                HttpResponses.of(
                        HttpStatus.FORBIDDEN.code(),
                        Map.of(),
                        HttpBody.of(FileUtils.readBytes(GitHubRateLimitInfoTest.class, "secondary-rate-limit.json"))));
    }

    private static Stream<HttpResponse> nonRateLimitedResponses() {
        return Stream.of(
                new HttpResponse(HttpStatus.FORBIDDEN.code(), HttpHeaders.empty(), null),
                HttpResponses.of(HttpStatus.FORBIDDEN.code(), Map.of(RETRY_AFTER, List.of("30")), null),
                new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), null));
    }

    @Test
    void from_shouldClassifyPrimary_whenForbiddenRemainingZeroWithNonRateLimitBody() {
        final HttpResponse forbidden = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0")),
                HttpBody.of("{\"message\":\"Resource not accessible by integration\"}".getBytes(UTF_8)));

        assertThat(GitHubRateLimitInfo.from(forbidden, FIXED_CLOCK))
                .get()
                .extracting(GitHubRateLimitInfo::kind)
                .isEqualTo(GitHubRateLimitInfo.Kind.PRIMARY);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"message\":\"You have triggered an abuse detection mechanism. Please wait a few minutes.\"}",
                "{\"message\":\"You have exceeded a Secondary Rate Limit.\"}",
                "{\"message\":\"Abuse Detection triggered\"}"
            })
    void from_shouldClassifySecondary_whenForbiddenBodyUsesCurrentOrLegacyWording(final String body) {
        final HttpResponse forbidden =
                HttpResponses.of(HttpStatus.FORBIDDEN.code(), Map.of(), HttpBody.of(body.getBytes(UTF_8)));

        assertThat(GitHubRateLimitInfo.from(forbidden, FIXED_CLOCK))
                .get()
                .extracting(GitHubRateLimitInfo::kind)
                .isEqualTo(GitHubRateLimitInfo.Kind.SECONDARY);
    }

    @Test
    void from_shouldIgnoreWording_whenItAppearsAfterTheScannedPrefix() {
        final String padding = "x".repeat(9000);
        final HttpResponse forbidden = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(), Map.of(), HttpBody.of((padding + "secondary rate limit").getBytes(UTF_8)));

        assertThat(GitHubRateLimitInfo.from(forbidden, FIXED_CLOCK)).isEmpty();
    }

    @Test
    void from_shouldLeaveRetryAfterNull_whenHttpDateIsBeforeLocalClock() {
        final Clock skewed = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.TOO_MANY_REQUESTS.code(),
                Map.of(RETRY_AFTER, List.of("Wed, 21 Oct 2015 07:28:30 GMT")),
                null);

        final Optional<GitHubRateLimitInfo> info = GitHubRateLimitInfo.from(limited, skewed);

        assertThat(info.orElseThrow().retryAfter()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("classificationRows")
    void from_shouldClassifyAsDocumented_whenStatusMessageAndHeadersVary(
            final String name, final HttpResponse response, final GitHubRateLimitInfo.Kind expected) {
        final Optional<GitHubRateLimitInfo> info = GitHubRateLimitInfo.from(response, FIXED_CLOCK);

        assertThat(info.map(GitHubRateLimitInfo::kind)).isEqualTo(Optional.ofNullable(expected));
    }

    private static Stream<Arguments> classificationRows() {
        final String secondary = "{\"message\":\"You have exceeded a secondary rate limit.\"}";
        final String docsOnly = "{\"message\":\"Slow down\",\"documentation_url\":\"https://docs.github.com/rest/"
                + "overview/rate-limits-for-the-rest-api#about-secondary-rate-limits\"}";
        final String abuseDocs = "{\"message\":\"Slow down\",\"documentation_url\":\"https://docs.github.com/"
                + "v3/#abuse-rate-limits\"}";
        final String permission = "{\"message\":\"Resource not accessible by integration\"}";
        final String padded =
                "{\"message\":\"You have exceeded a secondary rate limit.\",\"pad\":\"" + "x".repeat(9000) + "\"}";
        return Stream.of(
                Arguments.of(
                        "429 secondary message",
                        response(429, secondary, RATE_LIMIT_REMAINING, "5"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "429 secondary message beats remaining 0",
                        response(429, secondary, RATE_LIMIT_REMAINING, "0"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "429 remaining 0",
                        response(429, null, RATE_LIMIT_REMAINING, "0"),
                        GitHubRateLimitInfo.Kind.PRIMARY),
                Arguments.of(
                        "429 remaining 00",
                        response(429, null, RATE_LIMIT_REMAINING, "00"),
                        GitHubRateLimitInfo.Kind.PRIMARY),
                Arguments.of(
                        "429 remaining not a number",
                        response(429, null, RATE_LIMIT_REMAINING, "x"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of("429 otherwise", response(429, null), GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of("403 secondary message", response(403, secondary), GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 secondary documentation_url", response(403, docsOnly), GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 abuse documentation_url", response(403, abuseDocs), GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 secondary message with remaining 0",
                        response(403, secondary, RATE_LIMIT_REMAINING, "0"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 remaining 0 with permission message",
                        response(403, permission, RATE_LIMIT_REMAINING, "0"),
                        GitHubRateLimitInfo.Kind.PRIMARY),
                Arguments.of(
                        "403 quota left with permission message",
                        response(403, permission, RATE_LIMIT_REMAINING, "4999"),
                        null),
                Arguments.of("403 Retry-After alone", response(403, null, RETRY_AFTER, "30"), null),
                Arguments.of(
                        "403 Retry-After with remaining",
                        response(403, null, RETRY_AFTER, "30", RATE_LIMIT_REMAINING, "4000"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 Retry-After with limit",
                        response(403, null, RETRY_AFTER, "30", RATE_LIMIT_LIMIT, "5000"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 zero Retry-After with remaining",
                        response(403, null, RETRY_AFTER, "0", RATE_LIMIT_REMAINING, "4000"),
                        null),
                Arguments.of(
                        "403 garbage Retry-After with remaining",
                        response(403, null, RETRY_AFTER, "soon", RATE_LIMIT_REMAINING, "4000"),
                        null),
                Arguments.of("403 plain", response(403, null), null),
                Arguments.of(
                        "403 non-JSON secondary wording",
                        response(403, "Secondary Rate Limit!"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 JSON array with wording",
                        response(403, "[\"secondary rate limit\"]"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of(
                        "403 large JSON with early wording", response(403, padded), GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of("403 JSON message without wording", response(403, "{\"message\":\"Nope\"}"), null),
                Arguments.of(
                        "HEAD 403 Retry-After with remaining",
                        response(403, null, RETRY_AFTER, "60", RATE_LIMIT_REMAINING, "10"),
                        GitHubRateLimitInfo.Kind.SECONDARY),
                Arguments.of("HEAD 403 remaining only", response(403, null, RATE_LIMIT_REMAINING, "10"), null),
                Arguments.of("500 is never a rate limit", response(500, secondary, RATE_LIMIT_REMAINING, "0"), null),
                Arguments.of("404 is never a rate limit", response(404, null, RETRY_AFTER, "30"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void from_shouldLeaveRetryAfterNull_whenItIsNotPositive(final String retryAfter) {
        final HttpResponse limited = response(429, null, RETRY_AFTER, retryAfter);

        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(limited, FIXED_CLOCK).orElseThrow();

        assertThat(info.retryAfter()).isNull();
    }

    @Test
    void from_shouldReadServerDateAndObservationTime_whenResponseHasDate() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final Instant now = date.plusSeconds(30);
        final HttpResponse limited = response(429, null, DATE, DateTimeUtils.toRfc1123(date));

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(info.serverDate()).isEqualTo(date);
            softly.assertThat(info.observedAt()).isEqualTo(now);
        });
    }

    @Test
    void from_shouldMeasureHttpDateRetryAfterAgainstServerDate_whenLocalClockIsOff() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(
                429,
                null,
                DATE,
                DateTimeUtils.toRfc1123(date),
                RETRY_AFTER,
                DateTimeUtils.toRfc1123(date.plusSeconds(30)));

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(
                        limited, Clock.fixed(date.plusSeconds(120), ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retryAt_shouldMoveResetToLocalClock_whenPrimaryLimitHasServerDate() {
        final Instant date = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(
                403,
                null,
                DATE,
                DateTimeUtils.toRfc1123(date),
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_RESET,
                Long.toString(date.plusSeconds(600).getEpochSecond()),
                RATE_LIMIT_RESOURCE,
                "core");

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(
                        limited, Clock.fixed(date.plusSeconds(30), ZoneOffset.UTC))
                .orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(info.kind()).isEqualTo(GitHubRateLimitInfo.Kind.PRIMARY);
            softly.assertThat(info.resource()).isEqualTo("core");
            softly.assertThat(info.retryAt()).isEqualTo(date.plusSeconds(630));
        });
    }

    @Test
    void retryAt_shouldUseLaterOfResetAndRetryAfter_whenPrimaryLimitHasBoth() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        final HttpResponse resetLater = response(
                403,
                null,
                RETRY_AFTER,
                "10",
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_RESET,
                Long.toString(now.plusSeconds(100).getEpochSecond()));
        final HttpResponse retryAfterLater = response(
                403,
                null,
                RETRY_AFTER,
                "200",
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_RESET,
                Long.toString(now.plusSeconds(100).getEpochSecond()));

        assertSoftly(softly -> {
            softly.assertThat(GitHubRateLimitInfo.from(resetLater, clock)
                            .orElseThrow()
                            .retryAt())
                    .isEqualTo(now.plusSeconds(100));
            softly.assertThat(GitHubRateLimitInfo.from(retryAfterLater, clock)
                            .orElseThrow()
                            .retryAt())
                    .isEqualTo(now.plusSeconds(200));
        });
    }

    @Test
    void retryAt_shouldWaitRetryAfter_whenSecondaryLimitHasIt() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(429, null, RETRY_AFTER, "45");

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(info.kind()).isEqualTo(GitHubRateLimitInfo.Kind.SECONDARY);
            softly.assertThat(info.retryAt()).isEqualTo(now.plusSeconds(45));
        });
    }

    @Test
    void retryAt_shouldWaitForReset_whenSecondaryLimitHasNoRetryAfterAndZeroRemaining() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(
                403,
                "{\"message\":\"secondary rate limit\"}",
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_RESET,
                Long.toString(now.plusSeconds(90).getEpochSecond()));

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.retryAt()).isEqualTo(now.plusSeconds(90));
    }

    @Test
    void retryAt_shouldWaitSixtySeconds_whenSecondaryLimitNamesNoWait() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(429, null);

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.retryAt()).isEqualTo(now.plus(GitHubRateLimitInfo.DEFAULT_SECONDARY_WAIT));
    }

    @Test
    void retryAt_shouldWaitSixtySeconds_whenPrimaryLimitHasNeitherResetNorRetryAfter() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(403, null, RATE_LIMIT_REMAINING, "0");

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.retryAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void retryAt_shouldWaitRetryAfter_whenPrimaryLimitHasNoReset() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(403, null, RATE_LIMIT_REMAINING, "0", RETRY_AFTER, "20");

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.retryAt()).isEqualTo(now.plusSeconds(20));
    }

    @ParameterizedTest
    @MethodSource("secondaryLimitsThatDoNotNameAWait")
    void retryAt_shouldWaitSixtySeconds_whenSecondaryLimitCannotUseTheReset(final HttpResponse limited) {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.retryAt()).isEqualTo(now.plusSeconds(60));
    }

    private static Stream<HttpResponse> secondaryLimitsThatDoNotNameAWait() {
        final String message = "{\"message\":\"secondary rate limit\"}";
        final String reset = Long.toString(Instant.parse("2026-01-01T00:10:00Z").getEpochSecond());
        return Stream.of(
                response(403, message, RATE_LIMIT_REMAINING, "0"),
                response(403, message, RATE_LIMIT_REMAINING, "5", RATE_LIMIT_RESET, reset),
                response(403, message, RATE_LIMIT_RESET, reset));
    }

    @Test
    void retryAt_shouldNotOverflow_whenResetIsNearTheLastInstant() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final HttpResponse limited = response(
                403,
                null,
                DATE,
                DateTimeUtils.toRfc1123(now.minus(Duration.ofDays(365L * 100))),
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_RESET,
                Long.toString(Instant.MAX.getEpochSecond()));

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.retryAt()).isEqualTo(Instant.MAX);
    }

    @Test
    void waitFrom_shouldBeNonNegative_whenRetryAtHasPassed() {
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(
                        response(429, null, RETRY_AFTER, "45"), Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(info.waitFrom(now)).isEqualTo(Duration.ofSeconds(45));
            softly.assertThat(info.waitFrom(now.plusSeconds(100))).isEqualTo(Duration.ZERO);
        });
    }

    @Test
    void waitFrom_shouldThrow_whenNowIsNull() {
        final GitHubRateLimitInfo info =
                GitHubRateLimitInfo.from(response(429, null), FIXED_CLOCK).orElseThrow();

        final Throwable thrown = catchThrowable(() -> info.waitFrom(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("now");
    }

    @Test
    void describe_shouldNameKindResourceAndRetryTime_whenResourceKnown() {
        final Instant now = Instant.parse("2026-10-09T12:00:00Z");
        final HttpResponse limited = response(429, null, RATE_LIMIT_RESOURCE, "core");

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.describe())
                .isEqualTo("secondary rate limit; resource core; retry at 2026-10-09T12:01:00Z, in 60 s");
    }

    @Test
    void describe_shouldOmitResource_whenUnknown() {
        final Instant now = Instant.parse("2026-10-09T12:00:00Z");
        final HttpResponse limited = response(403, null, RATE_LIMIT_REMAINING, "0");

        final GitHubRateLimitInfo info = GitHubRateLimitInfo.from(limited, Clock.fixed(now, ZoneOffset.UTC))
                .orElseThrow();

        assertThat(info.describe()).isEqualTo("primary rate limit; retry at 2026-10-09T12:01:00Z, in 60 s");
    }
}
