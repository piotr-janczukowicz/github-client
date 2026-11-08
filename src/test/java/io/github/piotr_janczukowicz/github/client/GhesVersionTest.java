package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GhesVersionTest {

    @Test
    void constructor_shouldKeepTheNumbers_whenValid() {
        final GhesVersion version = new GhesVersion(3, 22);

        assertSoftly(softly -> {
            softly.assertThat(version.major()).isEqualTo(3);
            softly.assertThat(version.minor()).isEqualTo(22);
            softly.assertThat(version).hasToString("3.22");
        });
    }

    @Test
    void constructor_shouldThrow_whenMajorIsBelowOne() {
        final Throwable thrown = catchThrowable(() -> new GhesVersion(0, 1));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("major");
    }

    @Test
    void constructor_shouldThrow_whenMinorIsNegative() {
        final Throwable thrown = catchThrowable(() -> new GhesVersion(3, -1));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minor");
    }

    @Test
    void parse_shouldReadMajorAndMinor_whenTextIsWellFormed() {
        final GhesVersion version = GhesVersion.parse("3.21");

        assertThat(version).isEqualTo(new GhesVersion(3, 21));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "3", "3.", ".22", "3.22.1", "v3.22", "3,22", " 3.22", "3.22 ", "three.two", "3.-1"})
    void parse_shouldThrow_whenTextIsNotMajorDotMinor(final String text) {
        final Throwable thrown = catchThrowable(() -> GhesVersion.parse(text));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3.22");
    }

    @Test
    void parse_shouldThrow_whenTextIsNull() {
        final Throwable thrown = catchThrowable(() -> GhesVersion.parse(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("text");
    }

    @Test
    void compareTo_shouldOrderByMajorThenMinor_whenVersionsDiffer() {
        final List<GhesVersion> sorted = Stream.of(
                        new GhesVersion(3, 9), new GhesVersion(4, 0), new GhesVersion(3, 22), new GhesVersion(3, 10))
                .sorted()
                .toList();

        assertThat(sorted)
                .containsExactly(
                        new GhesVersion(3, 9), new GhesVersion(3, 10), new GhesVersion(3, 22), new GhesVersion(4, 0));
    }

    @Test
    void compareTo_shouldBeZero_whenVersionsAreEqual() {
        final int comparison = new GhesVersion(3, 22).compareTo(GhesVersion.parse("3.22"));

        assertThat(comparison).isZero();
    }

    @Test
    void supported_shouldBeSortedAndNotEmpty_whenCalled() {
        final List<GhesVersion> supported = GhesVersion.supported();

        assertThat(supported).isNotEmpty().isSorted();
    }

    @Test
    void supported_shouldBeUnmodifiable_whenCalled() {
        final List<GhesVersion> supported = GhesVersion.supported();
        final GhesVersion extra = new GhesVersion(9, 9);

        final Throwable thrown = catchThrowable(() -> supported.add(extra));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }
}
