package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.piotr_janczukowicz.openapi.model.CatalogSchema;
import io.github.piotr_janczukowicz.util.EnumUtils;

/** Issue search type reported in the {@code search_type} field of issue search responses. */
@CatalogSchema("operations/search/issues-and-pull-requests/200/properties/search_type")
public enum GitHubIssueSearchType {
    LEXICAL("lexical"),
    SEMANTIC("semantic"),
    HYBRID("hybrid"),
    /** A value this library version does not know. */
    UNRECOGNIZED("unrecognized");

    private static final EnumUtils.WireIndex<GitHubIssueSearchType> WIRE_INDEX =
            EnumUtils.index(GitHubIssueSearchType.class, GitHubIssueSearchType::wireValue);

    private final String wireValue;

    GitHubIssueSearchType(final String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static GitHubIssueSearchType fromWire(final String value) {
        requireNonNull(value, "value");

        return WIRE_INDEX.fromWire(value, UNRECOGNIZED);
    }
}
