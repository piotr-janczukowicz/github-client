package io.github.piotr_janczukowicz.github.json;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ext.javatime.deser.InstantDeserializer;

/**
 * Decodes {@link Instant} values, reading the shape GitHub sends ({@code yyyy-MM-ddTHH:mm:ssZ}) without a
 * {@link java.time.format.DateTimeFormatter}, which builds a parse context for every timestamp.
 *
 * <p>Every other input (fractional seconds, an offset, a leap second, a number, an out-of-range field, a malformed
 * string) is passed unchanged to Jackson's own {@link InstantDeserializer}, so accepted values, failures and messages
 * stay the same as before.
 */
final class GitHubInstantDeserializer extends StdDeserializer<Instant> {

    private static final int FAST_LENGTH = 20;
    private static final long SECONDS_PER_DAY = 86_400;
    private static final long SECONDS_PER_HOUR = 3_600;
    private static final long SECONDS_PER_MINUTE = 60;
    private static final int MONTHS_PER_YEAR = 12;
    private static final int MAX_HOUR = 23;
    private static final int MAX_MINUTE_OR_SECOND = 59;

    GitHubInstantDeserializer() {
        super(Instant.class);
    }

    @Override
    public Instant deserialize(final JsonParser parser, final DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            final Instant parsed = parseUtcSeconds(parser.getString());
            if (parsed != null) {
                return parsed;
            }
        }
        return InstantDeserializer.INSTANT.deserialize(parser, context);
    }

    private static @Nullable Instant parseUtcSeconds(final String text) {
        if (text.length() != FAST_LENGTH
                || text.charAt(4) != '-'
                || text.charAt(7) != '-'
                || text.charAt(10) != 'T'
                || text.charAt(13) != ':'
                || text.charAt(16) != ':'
                || text.charAt(19) != 'Z') {
            return null;
        }
        final int year = digits(text, 0, 4);
        final int month = digits(text, 5, 7);
        final int day = digits(text, 8, 10);
        final int hour = digits(text, 11, 13);
        final int minute = digits(text, 14, 16);
        final int second = digits(text, 17, 19);
        if ((year | month | day | hour | minute | second) < 0
                || month < 1
                || month > MONTHS_PER_YEAR
                || day < 1
                || hour > MAX_HOUR
                || minute > MAX_MINUTE_OR_SECOND
                || second > MAX_MINUTE_OR_SECOND) {
            return null;
        }
        final LocalDate firstOfMonth = LocalDate.of(year, month, 1);
        if (day > firstOfMonth.lengthOfMonth()) {
            return null;
        }
        final long epochDay = firstOfMonth.toEpochDay() + day - 1;
        return Instant.ofEpochSecond(
                epochDay * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second);
    }

    private static int digits(final String text, final int from, final int to) {
        int value = 0;
        for (int index = from; index < to; index++) {
            final int digit = text.charAt(index) - '0';
            if (digit < 0 || digit > 9) {
                return -1;
            }
            value = value * 10 + digit;
        }
        return value;
    }
}
