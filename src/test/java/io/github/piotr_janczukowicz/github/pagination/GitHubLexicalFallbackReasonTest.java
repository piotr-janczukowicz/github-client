package io.github.piotr_janczukowicz.github.pagination;

import io.github.piotr_janczukowicz.test.BaseWireEnumTest;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.provider.Arguments;

@UnitTest
class GitHubLexicalFallbackReasonTest extends BaseWireEnumTest<GitHubLexicalFallbackReason> {

    @Override
    protected Function<@Nullable String, GitHubLexicalFallbackReason> fromWire() {
        return GitHubLexicalFallbackReason::fromWire;
    }

    @Override
    protected Function<GitHubLexicalFallbackReason, String> wireValue() {
        return GitHubLexicalFallbackReason::wireValue;
    }

    @Override
    protected GitHubLexicalFallbackReason unrecognized() {
        return GitHubLexicalFallbackReason.UNRECOGNIZED;
    }

    @Override
    protected Stream<Arguments> knownValues() {
        return Stream.of(
                Arguments.of("no_text_terms", GitHubLexicalFallbackReason.NO_TEXT_TERMS),
                Arguments.of("quoted_text", GitHubLexicalFallbackReason.QUOTED_TEXT),
                Arguments.of("non_issue_target", GitHubLexicalFallbackReason.NON_ISSUE_TARGET),
                Arguments.of("or_boolean_not_supported", GitHubLexicalFallbackReason.OR_BOOLEAN_NOT_SUPPORTED),
                Arguments.of("no_accessible_repos", GitHubLexicalFallbackReason.NO_ACCESSIBLE_REPOS),
                Arguments.of("server_error", GitHubLexicalFallbackReason.SERVER_ERROR),
                Arguments.of(
                        "only_non_semantic_fields_requested",
                        GitHubLexicalFallbackReason.ONLY_NON_SEMANTIC_FIELDS_REQUESTED),
                Arguments.of("service_unavailable", GitHubLexicalFallbackReason.SERVICE_UNAVAILABLE));
    }
}
