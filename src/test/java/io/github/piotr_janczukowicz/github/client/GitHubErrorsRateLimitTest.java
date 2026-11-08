package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitInfo;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.DateTimeUtils;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@UnitTest
@Isolated
class GitHubErrorsRateLimitTest {

    private static final URI REQUEST_URI = URI.create("https://api.github.com/user");
    private static final Instant DATE = Instant.parse("2026-10-09T12:00:00Z");
    private static final String SECONDARY_BODY = "{\"message\":\"You have exceeded a secondary rate limit.\"}";
    private static final String PRIMARY_BODY = "{\"message\":\"API rate limit exceeded\"}";

    @Test
    void toException_shouldDescribePrimaryForbidden_whenQuotaIsSpent() {
        final Instant now = DATE.plusSeconds(30);
        final GitHubRateLimitException exception = rateLimit(
                403,
                PRIMARY_BODY,
                now,
                HttpHeaderNames.DATE,
                DateTimeUtils.toRfc1123(DATE),
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_RESET,
                Long.toString(DATE.plusSeconds(600).getEpochSecond()),
                RATE_LIMIT_RESOURCE,
                "core");

        assertSoftly(softly -> {
            softly.assertThat(exception.kind()).contains(GitHubRateLimitInfo.Kind.PRIMARY);
            softly.assertThat(exception.resource()).contains("core");
            softly.assertThat(exception.retryAt()).contains(DATE.plusSeconds(630));
            softly.assertThat(exception.rateLimit().orElseThrow().retryAfter()).isNull();
            softly.assertThat(exception.getMessage())
                    .isEqualTo("API rate limit exceeded [primary rate limit; resource core; retry at "
                            + "2026-10-09T12:10:30Z, in 600 s]");
        });
    }

    @Test
    void toException_shouldDescribePrimaryTooManyRequests_whenQuotaIsSpent() {
        final GitHubRateLimitException exception = rateLimit(
                429,
                PRIMARY_BODY,
                DATE,
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_RESET,
                Long.toString(DATE.plusSeconds(120).getEpochSecond()),
                RATE_LIMIT_RESOURCE,
                "search");

        assertSoftly(softly -> {
            softly.assertThat(exception.kind()).contains(GitHubRateLimitInfo.Kind.PRIMARY);
            softly.assertThat(exception.resource()).contains("search");
            softly.assertThat(exception.retryAt()).contains(DATE.plusSeconds(120));
            softly.assertThat(exception.getMessage())
                    .endsWith("[primary rate limit; resource search; retry at " + "2026-10-09T12:02:00Z, in 120 s]");
        });
    }

    @Test
    void toException_shouldDescribeSecondaryTooManyRequests_whenRetryAfterGiven() {
        final GitHubRateLimitException exception =
                rateLimit(429, null, DATE, RETRY_AFTER, "45", RATE_LIMIT_RESOURCE, "core", RATE_LIMIT_REMAINING, "100");

        assertSoftly(softly -> {
            softly.assertThat(exception.kind()).contains(GitHubRateLimitInfo.Kind.SECONDARY);
            softly.assertThat(exception.rateLimit().orElseThrow().retryAfter()).isEqualTo(Duration.ofSeconds(45));
            softly.assertThat(exception.retryAt()).contains(DATE.plusSeconds(45));
            softly.assertThat(exception.getMessage())
                    .endsWith("[secondary rate limit; resource core; retry at 2026-10-09T12:00:45Z, in 45 s]");
        });
    }

    @Test
    void toException_shouldRetryInSixtySeconds_whenSecondaryLimitNamesNoWait() {
        final GitHubRateLimitException exception = rateLimit(403, SECONDARY_BODY, DATE);

        assertSoftly(softly -> {
            softly.assertThat(exception.kind()).contains(GitHubRateLimitInfo.Kind.SECONDARY);
            softly.assertThat(exception.resource()).isEmpty();
            softly.assertThat(exception.retryAt()).contains(DATE.plusSeconds(60));
            softly.assertThat(exception.getMessage())
                    .isEqualTo("You have exceeded a secondary rate limit. [secondary rate limit; retry at "
                            + "2026-10-09T12:01:00Z, in 60 s]");
        });
    }

    @Test
    void toException_shouldStillCapTheMessage_whenGitHubMessageIsHuge() {
        final String body = "{\"message\":\"secondary rate limit " + "x".repeat(3000) + "\"}";

        final GitHubRateLimitException exception = rateLimit(403, body, DATE);

        assertThat(exception.getMessage()).endsWith("[truncated]").hasSizeLessThan(1100);
    }

    @Test
    void accessors_shouldBeEmpty_whenExceptionHasNoRateLimitInfo() {
        final GitHubRateLimitException exception =
                new GitHubRateLimitException(429, "rate", null, null, null, HttpHeaders.empty(), null, null);

        assertSoftly(softly -> {
            softly.assertThat(exception.retryAt()).isEmpty();
            softly.assertThat(exception.kind()).isEmpty();
            softly.assertThat(exception.resource()).isEmpty();
            softly.assertThat(exception.getMessage()).isEqualTo("rate");
        });
    }

    @Test
    void toException_shouldReadSystemClock_whenSystemClockIsGiven() {
        final HttpResponse response = new HttpResponse(429, HttpHeaders.of(RETRY_AFTER, "45"), null);

        final GitHubApiException exception =
                GitHubErrors.toException(HttpMethod.GET, response, REQUEST_URI, Clock.systemUTC());

        assertThat(((GitHubRateLimitException) exception).retryAt().orElseThrow())
                .isAfter(Instant.now().plusSeconds(30));
    }

    private static GitHubRateLimitException rateLimit(
            final int status, final String body, final Instant now, final String... namesAndValues) {
        final HttpResponse response = response(status, body, namesAndValues);
        return (GitHubRateLimitException)
                GitHubErrors.toException(HttpMethod.GET, response, REQUEST_URI, Clock.fixed(now, ZoneOffset.UTC));
    }
}
