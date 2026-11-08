package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpResult;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubPollIntervalTest {

    @Test
    void constructor_shouldThrow_whenInvoked() throws Exception {
        final Constructor<GitHubPollInterval> constructor = GitHubPollInterval.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void of_shouldThrow_whenResultIsNull() {
        final Throwable thrown = catchThrowable(() -> GitHubPollInterval.of(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("result");
    }

    @Test
    void of_shouldBeSixtySeconds_whenHeaderIsSixty() {
        final HttpResult<Void> result = resultWith(Map.of(GitHubHeaders.POLL_INTERVAL, List.of("60")));

        assertThat(GitHubPollInterval.of(result)).contains(Duration.ofSeconds(60));
    }

    @Test
    void of_shouldBeZero_whenHeaderIsZero() {
        final HttpResult<Void> result = resultWith(Map.of(GitHubHeaders.POLL_INTERVAL, List.of("0")));

        assertThat(GitHubPollInterval.of(result)).contains(Duration.ZERO);
    }

    @Test
    void of_shouldBeEmpty_whenHeaderIsMissing() {
        final HttpResult<Void> result = resultWith(Map.of());

        assertThat(GitHubPollInterval.of(result)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-5", "1.5", "99999999999999999999"})
    void of_shouldBeEmpty_whenHeaderIsNotWholeNonNegativeSeconds(final String value) {
        final HttpResult<Void> result = resultWith(Map.of(GitHubHeaders.POLL_INTERVAL, List.of(value)));

        assertThat(GitHubPollInterval.of(result)).isEmpty();
    }

    private static HttpResult<Void> resultWith(final Map<String, List<String>> headers) {
        final HttpResponse response = HttpResponses.of(HttpStatus.OK.code(), headers, null);
        return new HttpResult<>(response, null);
    }
}
