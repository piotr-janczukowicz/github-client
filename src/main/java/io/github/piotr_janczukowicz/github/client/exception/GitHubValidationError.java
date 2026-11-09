package io.github.piotr_janczukowicz.github.client.exception;

import java.io.Serial;
import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * One entry from GitHub's {@code errors} array on a validation failure.
 *
 * @param resource GitHub resource name, or {@code null}
 * @param field JSON field GitHub rejected, or {@code null}
 * @param code GitHub error code such as {@code missing_field}, or {@code null}
 * @param message human-readable detail, or {@code null}
 */
public record GitHubValidationError(
        @Nullable String resource,
        @Nullable String field,
        @Nullable String code,
        @Nullable String message) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
