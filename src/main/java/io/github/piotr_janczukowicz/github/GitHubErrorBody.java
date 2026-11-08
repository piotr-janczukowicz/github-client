package io.github.piotr_janczukowicz.github;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * GitHub error JSON object: {@code message}, {@code documentation_url}, and {@code errors}.
 *
 * @param message GitHub {@code message}, or {@code null} when missing or not a string
 * @param documentationUrl GitHub {@code documentation_url}, or {@code null}
 * @param errors mapped {@code errors} entries (empty when absent or not an array)
 */
public record GitHubErrorBody(
        @Nullable String message, @Nullable String documentationUrl, List<GitHubErrorBody.Item> errors) {

    public GitHubErrorBody {
        errors = List.copyOf(requireNonNull(errors, "errors"));
    }

    /**
     * One {@code errors} entry: a string message or an object with resource/field/code/message.
     *
     * @param resource GitHub resource name, or {@code null}
     * @param field JSON field GitHub rejected, or {@code null}
     * @param code GitHub error code, or {@code null}
     * @param message human-readable detail, or {@code null}
     */
    public record Item(
            @Nullable String resource,
            @Nullable String field,
            @Nullable String code,
            @Nullable String message) {}
}
