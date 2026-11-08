package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@UnitTest
class GitHubFlavorTest {

    @ParameterizedTest
    @CsvSource({"CLOUD,PT1S", "GHEC,PT1S", "GHES,PT0S"})
    void minMutationInterval_shouldKeepOneSecondOutsideGhes_whenFlavorGiven(
            final GitHubFlavor flavor, final Duration expected) {
        final Duration interval = flavor.minMutationInterval();

        assertThat(interval).isEqualTo(expected);
    }
}
