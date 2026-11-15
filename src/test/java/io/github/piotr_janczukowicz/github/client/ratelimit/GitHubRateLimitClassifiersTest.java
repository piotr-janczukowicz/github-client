package io.github.piotr_janczukowicz.github.client.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubRateLimitClassifiersTest {

    @Test
    void constructor_shouldThrow_whenInvoked() throws Exception {
        final Constructor<GitHubRateLimitClassifiers> constructor =
                GitHubRateLimitClassifiers.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({"42,42", "' 7 ',7", "0,0", "-3,-3"})
    void integer_shouldReadAWholeNumber_whenTheValueHoldsOne(final String value, final int expected) {
        final Integer number = GitHubRateLimitClassifiers.integer(value);

        assertThat(number).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1.5", "+5", "٣", "99999999999999999999"})
    void integer_shouldBeNull_whenTheValueIsNotAWholeAsciiNumber(final String value) {
        final Integer number = GitHubRateLimitClassifiers.integer(value);

        assertThat(number).isNull();
    }

    @Test
    void epochSecond_shouldReadTheInstant_whenTheValueHoldsAWholeNumber() {
        final Instant instant = GitHubRateLimitClassifiers.epochSecond(" 1609844400 ");

        assertThat(instant).isEqualTo(Instant.parse("2021-01-05T11:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "+1609844400", "٣", "9223372036854775807"})
    void epochSecond_shouldBeNull_whenTheValueIsNotAnInstantThatInstantCanHold(final String value) {
        final Instant instant = GitHubRateLimitClassifiers.epochSecond(value);

        assertThat(instant).isNull();
    }
}
