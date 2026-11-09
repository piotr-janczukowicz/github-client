package io.github.piotr_janczukowicz.github.pagination;

import io.github.piotr_janczukowicz.test.BaseWireEnumTest;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.provider.Arguments;

@UnitTest
class GitHubIssueSearchTypeTest extends BaseWireEnumTest<GitHubIssueSearchType> {

    @Override
    protected Function<@Nullable String, GitHubIssueSearchType> fromWire() {
        return GitHubIssueSearchType::fromWire;
    }

    @Override
    protected Function<GitHubIssueSearchType, String> wireValue() {
        return GitHubIssueSearchType::wireValue;
    }

    @Override
    protected GitHubIssueSearchType unrecognized() {
        return GitHubIssueSearchType.UNRECOGNIZED;
    }

    @Override
    protected Stream<Arguments> knownValues() {
        return Stream.of(
                Arguments.of("lexical", GitHubIssueSearchType.LEXICAL),
                Arguments.of("semantic", GitHubIssueSearchType.SEMANTIC),
                Arguments.of("hybrid", GitHubIssueSearchType.HYBRID));
    }
}
