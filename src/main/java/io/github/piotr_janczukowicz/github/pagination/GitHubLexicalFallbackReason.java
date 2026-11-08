package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.piotr_janczukowicz.openapi.model.CatalogSchema;
import io.github.piotr_janczukowicz.util.EnumUtils;

/** Reason semantic or hybrid issue search fell back to lexical search. */
@CatalogSchema("operations/search/issues-and-pull-requests/200/properties/lexical_fallback_reason/items")
public enum GitHubLexicalFallbackReason {
    NO_TEXT_TERMS("no_text_terms"),
    QUOTED_TEXT("quoted_text"),
    NON_ISSUE_TARGET("non_issue_target"),
    OR_BOOLEAN_NOT_SUPPORTED("or_boolean_not_supported"),
    NO_ACCESSIBLE_REPOS("no_accessible_repos"),
    SERVER_ERROR("server_error"),
    ONLY_NON_SEMANTIC_FIELDS_REQUESTED("only_non_semantic_fields_requested"),
    SERVICE_UNAVAILABLE("service_unavailable"),
    /** A value this library version does not know. */
    UNRECOGNIZED("unrecognized");

    private static final EnumUtils.WireIndex<GitHubLexicalFallbackReason> WIRE_INDEX =
            EnumUtils.index(GitHubLexicalFallbackReason.class, GitHubLexicalFallbackReason::wireValue);

    private final String wireValue;

    GitHubLexicalFallbackReason(final String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static GitHubLexicalFallbackReason fromWire(final String value) {
        requireNonNull(value, "value");

        return WIRE_INDEX.fromWire(value, UNRECOGNIZED);
    }
}
