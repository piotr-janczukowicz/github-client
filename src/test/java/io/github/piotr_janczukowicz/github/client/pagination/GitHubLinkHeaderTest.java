package io.github.piotr_janczukowicz.github.client.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationException;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubLinkHeaderTest {

    @ParameterizedTest
    @MethodSource("io.github.piotr_janczukowicz.test.TestHosts#apiBases")
    void parse_shouldExtractNext_whenHeaderListsSeveralRelations(final URI base) {
        final GitHubLinkHeader links = GitHubLinkHeader.parse("<" + base.resolve("user?page=1") + ">; rel=\"prev\", "
                + "<" + base.resolve("user?page=3") + ">; rel=\"next\", "
                + "<" + base.resolve("user?page=1") + ">; rel=\"first\", "
                + "<" + base.resolve("user?page=5") + ">; rel=\"last\"");

        assertThat(links.next()).contains(base.resolve("user?page=3"));
    }

    @Test
    void parse_shouldIgnoreMalformedEntries_whenHeaderInvalid() {
        final GitHubLinkHeader links =
                GitHubLinkHeader.parse("not-a-link, <https://api.github.com/user>; rel=\"next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/user"));
    }

    @Test
    void parse_shouldExtractNext_whenRelListsSeveralNames() {
        final GitHubLinkHeader links =
                GitHubLinkHeader.parse("<https://api.github.com/user?page=2>; rel=\"prev next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/user?page=2"));
    }

    @Test
    void parse_shouldSkipEntry_whenRelIsOnlyWhitespace() {
        final GitHubLinkHeader links = GitHubLinkHeader.parse(
                "<https://api.github.com/user>; rel=\"   \", <https://api.github.com/user?page=2>; rel=\"next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/user?page=2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rel=\"next\"", "rel=next", "rel=\"Next\"", "REL=\"next\"", "rel = next"})
    void parse_shouldExtractNext_whenRelQuotedUnquotedOrMixedCase(final String rel) {
        final GitHubLinkHeader links = GitHubLinkHeader.parse("<https://api.github.com/user?page=2>; " + rel);

        assertThat(links.next()).contains(URI.create("https://api.github.com/user?page=2"));
    }

    @Test
    void parse_shouldThrow_whenNextTargetIsNotValidUri() {
        final Throwable thrown = catchThrowable(() -> GitHubLinkHeader.parse(
                "<https://user:pw@api.github.com/search?q=a b&access_token=secret#frag>; rel=\"next\", "
                        + "<https://api.github.com/user?page=2>; rel=\"next\""));

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("https://api.github.com/search")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("user:pw")
                .hasMessageNotContaining("frag");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    https://host/a b             | https://host/a b
                    https://host a               | https://host a
                    https://host/p q#frag        | https://host/p q
                    https://host/p q?x=secret    | https://host/p q
                    https://u:pw@host:8443/p q   | https://host:8443/p q
                    1x://host/p q                | <not a URI>
                    ://host/p q                  | <not a URI>
                    """)
    void parse_shouldDescribeOnlySchemeHostAndPath_whenNextTargetInvalid(
            final String target, final String description) {
        final Throwable thrown = catchThrowable(() -> GitHubLinkHeader.parse("<" + target + ">; rel=next"));

        assertThat(thrown).isInstanceOf(GitHubPaginationException.class).hasMessageEndingWith(": " + description);
    }

    @Test
    void parse_shouldDescribeGenerically_whenInvalidNextTargetHasNoScheme() {
        final Throwable thrown = catchThrowable(() -> GitHubLinkHeader.parse("<a b?token=secret>; rel=\"next\""));

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("<not a URI>")
                .hasMessageNotContaining("secret");
    }

    @Test
    void parse_shouldSkipSilently_whenOtherRelationHasInvalidUri() {
        final GitHubLinkHeader links =
                GitHubLinkHeader.parse("<http://[>; rel=\"prev\", <https://api.github.com/user?page=2>; rel=\"next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/user?page=2"));
    }

    @Test
    void parse_shouldIgnoreRelInsideQuotedParameter_whenTitleMentionsNext() {
        final GitHubLinkHeader links = GitHubLinkHeader.parse(
                "<https://api.github.com/a>; title=\"x; rel=next\"; rel=\"prev\", <https://api.github.com/b>; rel=next");

        assertThat(links.next()).contains(URI.create("https://api.github.com/b"));
    }

    @Test
    void parse_shouldExtractNext_whenEarlierEntryHasUnbalancedQuote() {
        final GitHubLinkHeader links = GitHubLinkHeader.parse(
                "<https://api.github.com/a>; title=\"x, <https://api.github.com/x?page=2>; rel=\"next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/x?page=2"));
    }

    @Test
    void parse_shouldExtractNext_whenUnquotedRelIsCommaTerminated() {
        final GitHubLinkHeader links = GitHubLinkHeader.parse(
                "<https://api.github.com/u>; rel=next, " + "<https://api.github.com/v>; rel=last");

        assertThat(links.next()).contains(URI.create("https://api.github.com/u"));
    }

    @Test
    void parse_shouldNotSplitEntry_whenOpeningBracketIsInsideQuotedParameter() {
        final GitHubLinkHeader links =
                GitHubLinkHeader.parse("<https://api.github.com/a>; title=\"a <b\"; rel=\"next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/a"));
    }

    @Test
    void parse_shouldExtractNext_whenRelIsNotFirstParameter() {
        final GitHubLinkHeader links =
                GitHubLinkHeader.parse("<https://api.github.com/user?page=2>; title=\"x\"; type=\"a,b\"; rel=\"next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/user?page=2"));
    }

    @Test
    void parse_shouldNotSplitEntry_whenCommaIsInsideUrl() {
        final GitHubLinkHeader links = GitHubLinkHeader.parse(
                "<https://api.github.com/user?a=1,2>; rel=\"next\", <https://api.github.com/user?page=1>; rel=\"prev\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/user?a=1,2"));
    }

    @Test
    void parse_shouldUseFirstNextEntry_whenSeveralEntriesNameNext() {
        final GitHubLinkHeader links = GitHubLinkHeader.parse(
                "<https://api.github.com/a>; rel=\"next\", <https://api.github.com/b>; rel=\"next\"");

        assertThat(links.next()).contains(URI.create("https://api.github.com/a"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "<https://api.github.com/user?page=1>; rel=\"prev\", <https://api.github.com/user?page=1>; rel=\"first\"",
                "<https://api.github.com/user?page=2>; rel=prev; rel=next",
                "<https://api.github.com/a>; title=\"x; rel=next\"",
                "<https://api.github.com/a>; rel",
                "<https://api.github.com/a>; rel=",
                "<https://api.github.com/a>;",
                "<https://api.github.com/a>; title=x, ",
                "<https://api.github.com/a>; title=x, <https://api.github.com/b",
                "<https://api.github.com/a>; rel=nextish"
            })
    void parse_shouldYieldNoNext_whenHeaderHasNoUsableNextRelation(final String header) {
        assertThat(GitHubLinkHeader.parse(header).next()).isEmpty();
    }

    @Test
    void parse_shouldThrow_whenHeaderValueNull() {
        final Throwable thrown = catchThrowable(() -> GitHubLinkHeader.parse(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("headerValue");
    }

    @Test
    void parse_shouldThrow_whenNextTargetHasUnencodedPipe() {
        final Throwable thrown = catchThrowable(() ->
                GitHubLinkHeader.parse("<https://api.github.com/search?q=a|b&access_token=secret>; rel=\"next\""));

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("https://api.github.com/search")
                .hasMessageNotContaining("secret");
    }
}
