package io.github.piotr_janczukowicz.github.json;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.json.JsonDecodeException;
import io.github.piotr_janczukowicz.json.JsonEncodeException;
import io.github.piotr_janczukowicz.json.JsonType;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link JsonCodec} backed by Jackson {@link JsonMapper}, created through {@link GitHubJsonCodecs}. Decode failures
 * surface as {@link JsonDecodeException}, including a value that a custom deserializer or a record constructor rejects
 * with an unchecked exception.
 */
final class GitHubJacksonCodec implements JsonCodec {

    private static final int MAX_PATH_LENGTH = 200;

    private final JsonMapper mapper;

    GitHubJacksonCodec(final JsonMapper mapper) {
        this.mapper = requireNonNull(mapper, "mapper");
    }

    /**
     * Reads {@code json} as the raw type {@code type} with the configured {@link JsonMapper}.
     *
     * @param json UTF-8 JSON bytes
     * @param type target class
     * @return decoded value, never {@code null}
     * @throws NullPointerException if {@code json} or {@code type} is {@code null}
     * @throws JsonDecodeException if {@code json} is malformed or does not fit the target type
     * @throws IllegalStateException if {@code json} is the literal {@code null}, which has no value to return
     */
    @Override
    public <T> T decode(final byte[] json, final Class<T> type) {
        requireNonNull(json, "json");
        requireNonNull(type, "type");

        final T value;
        try {
            value = mapper.readValue(json, type);
        } catch (final JacksonException e) {
            throw decodeFailure(type.getName(), e);
        } catch (final RuntimeException e) {
            throw rejectedValue(type.getName(), e);
        }
        return requireValue(value, type);
    }

    /**
     * Reads {@code json} as {@code type}, including generic type arguments, with the configured {@link JsonMapper}.
     *
     * @param json UTF-8 JSON bytes
     * @param type target type, including type arguments
     * @return decoded value, never {@code null}
     * @throws NullPointerException if {@code json} or {@code type} is {@code null}
     * @throws JsonDecodeException if {@code json} is malformed or does not fit the target type
     * @throws IllegalStateException if {@code json} is the literal {@code null}, which has no value to return
     */
    @Override
    public <T> T decode(final byte[] json, final JsonType<T> type) {
        requireNonNull(json, "json");
        requireNonNull(type, "type");

        final T value;
        try {
            value = mapper.readValue(json, mapper.constructType(type.javaType()));
        } catch (final JacksonException e) {
            throw decodeFailure(type.javaType().getTypeName(), e);
        } catch (final RuntimeException e) {
            throw rejectedValue(type.javaType().getTypeName(), e);
        }
        return requireValue(value, type.javaType());
    }

    /**
     * Writes {@code value} as UTF-8 JSON bytes with the configured {@link JsonMapper}.
     *
     * @param value value to write
     * @return JSON entity bytes
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws JsonEncodeException if {@code value} cannot be written as JSON
     */
    @Override
    public byte[] encode(final Object value) {
        requireNonNull(value, "value");

        try {
            return mapper.writeValueAsBytes(value);
        } catch (final JacksonException e) {
            throw new JsonEncodeException("Cannot encode " + value.getClass().getName() + " as JSON", e);
        }
    }

    // Names the kind of failure and where it happened (the field path), but never the offending text: Jackson's own
    // message quotes the value it could not read, and the body may hold secrets. The cause keeps the full detail.
    private static JsonDecodeException decodeFailure(final String typeName, final JacksonException cause) {
        final String path = safe(cause.getPathReference());
        final String where = path.isBlank() ? "" : " at " + path;
        return new JsonDecodeException(
                "Cannot decode JSON as " + typeName + where + " ("
                        + cause.getClass().getSimpleName() + ")",
                cause);
    }

    // A custom deserializer at the root, or a constructor it calls, can reject a value with an unchecked exception that
    // Jackson does not wrap. It is still a body that does not fit the type, so it surfaces as the same decode failure.
    private static JsonDecodeException rejectedValue(final String typeName, final RuntimeException cause) {
        return new JsonDecodeException(
                "Cannot decode JSON as " + typeName + " (" + cause.getClass().getSimpleName() + ")", cause);
    }

    // A key in the path comes from the response body, so it can hold line breaks and other control characters (log
    // forging) and be very long: both are limited before the path goes into a message.
    private static String safe(final String path) {
        final String limited = path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) + "..." : path;
        final StringBuilder clean = new StringBuilder(limited.length());
        for (int index = 0; index < limited.length(); index++) {
            final char c = limited.charAt(index);
            clean.append(Character.isISOControl(c) ? '?' : c);
        }
        return clean.toString();
    }

    // The type name is only built when the value is missing: it costs an allocation on every decode otherwise.
    private static <T> T requireValue(final @Nullable T value, final Type type) {
        if (value == null) {
            throw new IllegalStateException("JSON body is null, expected a value of type " + type.getTypeName());
        }
        return value;
    }
}
