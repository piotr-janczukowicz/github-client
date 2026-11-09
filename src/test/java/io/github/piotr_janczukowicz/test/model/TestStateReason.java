package io.github.piotr_janczukowicz.test.model;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.piotr_janczukowicz.util.EnumUtils;

/** An enum shaped like the generated wire enums: a wire value, and a {@code fromWire} that rejects an unknown value. */
public enum TestStateReason {
    COMPLETED("completed"),
    NOT_PLANNED("not_planned");

    private static final EnumUtils.WireIndex<TestStateReason> WIRE_INDEX =
            EnumUtils.index(TestStateReason.class, TestStateReason::wireValue);

    private final String wireValue;

    TestStateReason(final String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static TestStateReason fromWire(final String value) {
        requireNonNull(value, "value");

        return WIRE_INDEX
                .fromWire(value)
                .orElseThrow(() -> new IllegalArgumentException("Unknown test state reason: " + value));
    }
}
