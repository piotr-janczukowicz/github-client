package io.github.piotr_janczukowicz.github.json;

import io.github.piotr_janczukowicz.json.jackson.module.immutable.ImmutableCollectionsModule;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Configured Jackson {@link JsonMapper} for GitHub JSON.
 *
 * <p>GitHub REST uses snake_case field names and may include properties a given type does not declare. Instants are
 * written as ISO-8601. JSON numbers and booleans bound to {@link String} (simple {@code oneOf}/{@code anyOf} collapse)
 * are converted on decode. A JSON string bound to a {@link java.util.List} is read as a one-element list.
 *
 * <p>Lists, sets and maps decode as immutable collections, so a decoded model cannot be changed through its
 * collections. A JSON {@code null} or an absent property stays {@code null}, and {@code null} elements and map values
 * are kept.
 *
 * <p>The scalar coercions are lenient on purpose, because GitHub documents some fields with several types, but they
 * also accept a number where a string was meant, so a schema change of that kind decodes silently instead of failing.
 * Jackson's own coercions of a JSON string into a number or a boolean, and of a JSON number into a boolean, are also
 * still active; only a fractional number bound to an integer fails, because it would lose its fraction. A record
 * component of a primitive type whose property is missing from the JSON is {@code 0} or {@code false}, because failing
 * on a missing creator property would fail every record with an absent optional field; an explicit JSON {@code null}
 * for a primitive does fail.
 *
 * <p>An enum value this library does not know (GitHub adds values over time) decodes as {@code null} instead of failing
 * the whole response, so one new value does not break a page of otherwise valid items. The generated enums reject
 * unknown values in {@code fromWire}; Jackson turns that rejection into {@code null} only when reading. Building a
 * request payload with an enum constant or calling {@code fromWire} yourself is unaffected and still fails on an
 * invalid value. A property that is required (a record component that rejects {@code null}) still fails when its value
 * is unknown, except for the open-ended response enums that declare an {@code UNRECOGNIZED} constant: their
 * {@code fromWire} returns it, so the property stays non-null and the page survives.
 *
 * <p>A JSON object with the same key twice fails the decode instead of keeping one of the values: a discriminator read
 * from the first and a payload bound from the last could otherwise disagree. GitHub never sends duplicate keys.
 *
 * <p>An unknown discriminator value is different: where a deserializer picks the Java type from a {@code type} property
 * ({@code GitHubContentResultDeserializer}, {@code GitHubUserViewDeserializer},
 * {@code GitHubSecretScanningLocationDeserializer}, {@code GitHubProjectItemSimpleDeserializer}), a value that this
 * library does not know fails the decode instead of becoming {@code null}, because there is no type to build. The
 * library does not substitute placeholder data. The one exception is the event list: {@code GitHubEventDeserializer}
 * keeps the payload of an event type it does not know as a {@code GitHubUnknownEventPayload}, so one new event type
 * does not fail the page it is in.
 */
final class GitHubJsonMappers {

    private static final JsonMapper DEFAULTS = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .withCoercionConfig(LogicalType.Textual, cfg -> {
                cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.TryConvert);
                cfg.setCoercion(CoercionInputShape.Float, CoercionAction.TryConvert);
                cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.TryConvert);
            })
            .withCoercionConfig(
                    LogicalType.Collection,
                    cfg -> cfg.setCoercion(CoercionInputShape.String, CoercionAction.TryConvert))
            .addModule(new ImmutableCollectionsModule())
            .addModule(GitHubCoreModule.create())
            .addModules(serviceModules())
            .build();

    private GitHubJsonMappers() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Shared mapper that reads snake_case names, ignores unknown properties, and writes dates as ISO-8601.
     *
     * @return the default mapper
     */
    static JsonMapper defaults() {
        return DEFAULTS;
    }

    static List<JacksonModule> modulesOf(final Iterable<? extends GitHubJsonModule> providers) {
        final List<JacksonModule> modules = new ArrayList<>();
        for (final GitHubJsonModule provider : providers) {
            modules.add(provider.module());
        }
        return List.copyOf(modules);
    }

    private static List<JacksonModule> serviceModules() {
        return modulesOf(ServiceLoader.load(GitHubJsonModule.class));
    }
}
