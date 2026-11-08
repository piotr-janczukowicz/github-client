package io.github.piotr_janczukowicz.github.client.ratelimit;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubRateLimitSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void from_shouldReadAllHeaders_whenPresent() {
        final HttpHeaders headers = HttpHeaders.of(
                RATE_LIMIT_LIMIT, "5000",
                RATE_LIMIT_REMAINING, "4990",
                RATE_LIMIT_USED, "10",
                RATE_LIMIT_RESET, "1767225600",
                RATE_LIMIT_RESOURCE, "search");

        final Optional<GitHubRateLimitSnapshot> snapshot = GitHubRateLimitSnapshot.from(headers, NOW);

        assertThat(snapshot)
                .contains(new GitHubRateLimitSnapshot(
                        "search", 5000, 4990, 10, Instant.ofEpochSecond(1_767_225_600L), NOW));
    }

    @Test
    void from_shouldDeriveUsedAndDefaultResource_whenOnlyRequiredHeadersPresent() {
        final HttpHeaders headers =
                HttpHeaders.of(RATE_LIMIT_LIMIT, "60", RATE_LIMIT_REMAINING, "55", RATE_LIMIT_RESET, "1767225600");

        final GitHubRateLimitSnapshot snapshot =
                GitHubRateLimitSnapshot.from(headers, NOW).orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(snapshot.used()).isEqualTo(5);
            softly.assertThat(snapshot.resource()).isEqualTo(GitHubRateLimitSnapshot.UNKNOWN_RESOURCE);
            softly.assertThat(snapshot.resource()).isEqualTo("unknown");
        });
    }

    @Test
    void from_shouldDeriveUsed_whenReportedUsedIsNotANumber() {
        final HttpHeaders headers = HttpHeaders.of(
                RATE_LIMIT_LIMIT, "60",
                RATE_LIMIT_REMAINING, "55",
                RATE_LIMIT_USED, "many",
                RATE_LIMIT_RESET, "1767225600",
                RATE_LIMIT_RESOURCE, " ");

        final GitHubRateLimitSnapshot snapshot =
                GitHubRateLimitSnapshot.from(headers, NOW).orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(snapshot.used()).isEqualTo(5);
            softly.assertThat(snapshot.resource()).isEqualTo(GitHubRateLimitSnapshot.UNKNOWN_RESOURCE);
        });
    }

    @Test
    void from_shouldKeepZeroUsed_whenRemainingExceedsLimit() {
        final HttpHeaders headers =
                HttpHeaders.of(RATE_LIMIT_LIMIT, "10", RATE_LIMIT_REMAINING, "12", RATE_LIMIT_RESET, "1767225600");

        assertThat(GitHubRateLimitSnapshot.from(headers, NOW).orElseThrow().used())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit", "remaining", "reset"})
    void from_shouldBeEmpty_whenARequiredHeaderIsMissing(final String missing) {
        HttpHeaders headers = HttpHeaders.empty();
        if (!"limit".equals(missing)) {
            headers = headers.with(RATE_LIMIT_LIMIT, "60");
        }
        if (!"remaining".equals(missing)) {
            headers = headers.with(RATE_LIMIT_REMAINING, "55");
        }
        if (!"reset".equals(missing)) {
            headers = headers.with(RATE_LIMIT_RESET, "1767225600");
        }

        assertThat(GitHubRateLimitSnapshot.from(headers, NOW)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "9999999999999999999999"})
    void from_shouldBeEmpty_whenAHeaderIsInvalid(final String value) {
        final HttpHeaders headers =
                HttpHeaders.of(RATE_LIMIT_LIMIT, "60", RATE_LIMIT_REMAINING, value, RATE_LIMIT_RESET, "1767225600");

        assertThat(GitHubRateLimitSnapshot.from(headers, NOW)).isEmpty();
    }

    @Test
    void from_shouldBeEmpty_whenResetIsNotAnInstant() {
        final HttpHeaders headers = HttpHeaders.of(
                RATE_LIMIT_LIMIT, "60", RATE_LIMIT_REMAINING, "5", RATE_LIMIT_RESET, "9223372036854775807");

        assertThat(GitHubRateLimitSnapshot.from(headers, NOW)).isEmpty();
    }

    @Test
    void from_shouldBeEmpty_whenResetIsNotANumber() {
        final HttpHeaders headers =
                HttpHeaders.of(RATE_LIMIT_LIMIT, "60", RATE_LIMIT_REMAINING, "5", RATE_LIMIT_RESET, "tomorrow");

        assertThat(GitHubRateLimitSnapshot.from(headers, NOW)).isEmpty();
    }

    @Test
    void isExhausted_shouldReflectRemaining() {
        final Instant reset = NOW.plusSeconds(60);

        assertSoftly(softly -> {
            softly.assertThat(new GitHubRateLimitSnapshot("core", 60, 0, 60, reset, NOW).isExhausted())
                    .isTrue();
            softly.assertThat(new GitHubRateLimitSnapshot("core", 60, 1, 59, reset, NOW).isExhausted())
                    .isFalse();
        });
    }

    @Test
    void untilReset_shouldNeverBeNegative() {
        final GitHubRateLimitSnapshot snapshot =
                new GitHubRateLimitSnapshot("core", 60, 1, 59, NOW.plusSeconds(60), NOW);

        assertSoftly(softly -> {
            softly.assertThat(snapshot.untilReset(NOW)).isEqualTo(Duration.ofSeconds(60));
            softly.assertThat(snapshot.untilReset(NOW.plusSeconds(61))).isEqualTo(Duration.ZERO);
        });
    }

    @Test
    void constructor_shouldReject_whenValuesInvalid() {
        final Instant reset = NOW.plusSeconds(60);

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> new GitHubRateLimitSnapshot(" ", 1, 1, 0, reset, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> new GitHubRateLimitSnapshot("core", -1, 1, 0, reset, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> new GitHubRateLimitSnapshot("core", 1, 1, 0, null, NOW)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> GitHubRateLimitSnapshot.from(null, NOW)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> GitHubRateLimitSnapshot.from(HttpHeaders.empty(), null)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(
                            () -> new GitHubRateLimitSnapshot("core", 1, 1, 0, reset, NOW).untilReset(null)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void from_shouldBeEmpty_whenLimitIsNegative() {
        final HttpHeaders headers =
                HttpHeaders.of(RATE_LIMIT_LIMIT, "-5", RATE_LIMIT_REMAINING, "5", RATE_LIMIT_RESET, "1767225600");

        assertThat(GitHubRateLimitSnapshot.from(headers, NOW)).isEmpty();
    }

    @Test
    void from_shouldDeriveUsed_whenReportedUsedIsNegative() {
        final HttpHeaders headers = HttpHeaders.of(
                RATE_LIMIT_LIMIT, "60",
                RATE_LIMIT_REMAINING, "55",
                RATE_LIMIT_USED, "-3",
                RATE_LIMIT_RESET, "1767225600");

        assertThat(GitHubRateLimitSnapshot.from(headers, NOW).orElseThrow().used())
                .isEqualTo(5);
    }
}
