package io.github.piotr_janczukowicz.github.client.pagination;

import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.test.model.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test double of the codec for the pager tests: it reads the {@code "login"} and {@code "id"} pairs of a page body.
 */
final class UserCodec implements JsonCodec {

    private static final Pattern USER = Pattern.compile("\"login\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"id\"\\s*:\\s*(\\d+)");

    @Override
    public <T> T decode(final byte[] json, final Class<T> type) {
        throw new UnsupportedOperationException("the pager reads lists through a JsonType");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T decode(final byte[] json, final JsonType<T> type) {
        final Matcher matcher = USER.matcher(new String(json, StandardCharsets.UTF_8));
        final List<TestUser> users = new ArrayList<>();
        while (matcher.find()) {
            users.add(new TestUser(Long.parseLong(matcher.group(2)), matcher.group(1)));
        }
        return (T) List.copyOf(users);
    }

    @Override
    public byte[] encode(final Object value) {
        throw new UnsupportedOperationException("the pager tests never encode");
    }
}
