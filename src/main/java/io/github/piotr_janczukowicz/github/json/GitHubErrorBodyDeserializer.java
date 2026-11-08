package io.github.piotr_janczukowicz.github.json;

import io.github.piotr_janczukowicz.github.GitHubErrorBody;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

/** Reads a GitHub error object, skipping non-object roots and non-string/object error items. */
final class GitHubErrorBodyDeserializer extends StdDeserializer<GitHubErrorBody> {

    GitHubErrorBodyDeserializer() {
        super(GitHubErrorBody.class);
    }

    @Override
    public GitHubErrorBody deserialize(final JsonParser parser, final DeserializationContext context) {
        final JsonNode tree = context.readValue(parser, JsonNode.class);
        if (!tree.isObject()) {
            throw DatabindException.from(parser, "GitHub error body must be a JSON object");
        }
        return new GitHubErrorBody(
                text(tree, "message"), text(tree, "documentation_url"), parseErrors(tree.get("errors")));
    }

    private static List<GitHubErrorBody.Item> parseErrors(final @Nullable JsonNode errors) {
        if (errors == null || !errors.isArray()) {
            return List.of();
        }
        final List<GitHubErrorBody.Item> items = new ArrayList<>();
        for (final JsonNode item : errors) {
            if (item.isString()) {
                items.add(new GitHubErrorBody.Item(null, null, null, item.asString()));
            } else if (item.isObject()) {
                items.add(new GitHubErrorBody.Item(
                        text(item, "resource"), text(item, "field"), text(item, "code"), text(item, "message")));
            }
        }
        return List.copyOf(items);
    }

    private static @Nullable String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        return value.asString();
    }
}
