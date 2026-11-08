package io.github.piotr_janczukowicz.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseWireEnumTest<E extends Enum<E>> {

    protected abstract Function<@Nullable String, E> fromWire();

    protected abstract Function<E, String> wireValue();

    protected abstract E unrecognized();

    protected abstract Stream<Arguments> knownValues();

    @ParameterizedTest
    @MethodSource("knownValues")
    public void fromWire_shouldReturnConstant_whenKnownValue(final String wire, final E value) {
        final E decoded = fromWire().apply(wire);

        assertSoftly(softly -> {
            softly.assertThat(decoded).isEqualTo(value);
            softly.assertThat(wireValue().apply(decoded)).isEqualTo(wire);
        });
    }

    @Test
    public void fromWire_shouldThrow_whenValueNull() {
        final Throwable thrown = catchThrowable(() -> fromWire().apply(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("value");
    }

    @Test
    public void fromWire_shouldReturnUnrecognized_whenValueNotKnown() {
        final String wire = "brand_new_value";

        final E decoded = fromWire().apply(wire);

        assertSoftly(softly -> {
            softly.assertThat(decoded).isEqualTo(unrecognized());
            softly.assertThat(wireValue().apply(decoded)).isEqualTo("unrecognized");
        });
    }
}
