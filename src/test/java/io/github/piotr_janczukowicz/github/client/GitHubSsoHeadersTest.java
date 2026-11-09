package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.SSO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubSsoHeadersTest {

    @Test
    void constructor_shouldBeUninstantiable() throws Exception {
        final Constructor<GitHubSsoHeaders> constructor = GitHubSsoHeaders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void partialResultOrganizations_shouldListIds_whenPartialResults() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "partial-results; organizations=21955855,20582480");

        assertThat(GitHubSsoHeaders.partialResultOrganizations(headers)).containsExactly(21_955_855L, 20_582_480L);
    }

    @Test
    void partialResultOrganizations_shouldTolerateCaseAndWhitespace_whenHeaderIsWrittenLoosely() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "  Partial-Results ;  ORGANIZATIONS = 21955855 , 20582480  ");

        assertThat(GitHubSsoHeaders.partialResultOrganizations(headers)).containsExactly(21_955_855L, 20_582_480L);
    }

    @Test
    void partialResultOrganizations_shouldIgnoreMalformedIds_whenSomeIdsAreNotNumbers() {
        final HttpHeaders headers =
                HttpHeaders.of(SSO, "partial-results; organizations=12,,abc,-3,+4,7x,99999999999999999999,8");

        assertThat(GitHubSsoHeaders.partialResultOrganizations(headers)).containsExactly(12L, 8L);
    }

    @Test
    void partialResultOrganizations_shouldKeepId_whenNineteenDigitsFitLong() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "partial-results; organizations=9223372036854775807");

        assertThat(GitHubSsoHeaders.partialResultOrganizations(headers)).containsExactly(Long.MAX_VALUE);
    }

    @Test
    void partialResultOrganizations_shouldIgnoreOverflowingId_whenIdExceedsLong() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "partial-results; organizations=9223372036854775808,8");

        assertThat(GitHubSsoHeaders.partialResultOrganizations(headers)).containsExactly(8L);
    }

    @Test
    void partialResultOrganizations_shouldIgnoreZero_whenIdIsNotPositive() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "partial-results; organizations=0,8");

        assertThat(GitHubSsoHeaders.partialResultOrganizations(headers)).containsExactly(8L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial-results; extra", "partial-results; other=21955855"})
    void partialResultOrganizations_shouldBeEmpty_whenNoOrganizationsParameter(final String value) {
        final HttpHeaders headers = HttpHeaders.of(SSO, value);

        final List<Long> ids = GitHubSsoHeaders.partialResultOrganizations(headers);

        assertThat(ids).isEmpty();
    }

    @Test
    void partialResultOrganizations_shouldBeEmpty_whenHeaderIsAbsent() {
        assertThat(GitHubSsoHeaders.partialResultOrganizations(HttpHeaders.empty()))
                .isEmpty();
    }

    @Test
    void partialResultOrganizations_shouldBeEmpty_whenHeaderRequiresAuthorization() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "required; url=https://github.com/orgs/o/sso");

        final List<Long> ids = GitHubSsoHeaders.partialResultOrganizations(headers);

        assertThat(ids).isEmpty();
    }

    @Test
    void partialResultOrganizations_shouldBeEmpty_whenPartialResultsListNoOrganizations() {
        assertThat(GitHubSsoHeaders.partialResultOrganizations(HttpHeaders.of(SSO, "partial-results")))
                .isEmpty();
    }

    @Test
    void authorizationUrl_shouldReturnLink_whenHeaderHasUrl() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "required; url=https://github.com/orgs/o/sso; extra");

        assertThat(GitHubSsoHeaders.authorizationUrl(headers)).contains("https://github.com/orgs/o/sso");
    }

    @Test
    void authorizationUrl_shouldBeEmpty_whenHeaderHasNoUrl() {
        final Optional<String> withoutUrl = GitHubSsoHeaders.authorizationUrl(HttpHeaders.of(SSO, "required"));
        final Optional<String> absent = GitHubSsoHeaders.authorizationUrl(HttpHeaders.empty());

        assertSoftly(softly -> {
            softly.assertThat(withoutUrl).isEmpty();
            softly.assertThat(absent).isEmpty();
        });
    }

    @Test
    void logPartialResults_shouldLogCountOnce_whenPartialResults() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "partial-results; organizations=21955855,20582480");

        try (final LogCaptor logs = LogCaptor.forClass(GitHubSsoHeaders.class)) {
            logs.setLogLevelToDebug();
            GitHubSsoHeaders.logPartialResults(headers);

            assertThat(logs.getDebugLogs())
                    .singleElement()
                    .asString()
                    .contains("partial results")
                    .contains("2 SSO-protected organization(s)")
                    .doesNotContain("https://")
                    .doesNotContain("organizations=");
        }
    }

    @Test
    void logPartialResults_shouldNotLog_whenHeaderRequiresAuthorization() {
        final HttpHeaders headers = HttpHeaders.of(SSO, "required; url=https://github.com/orgs/o/sso");

        try (final LogCaptor logs = LogCaptor.forClass(GitHubSsoHeaders.class)) {
            logs.setLogLevelToDebug();
            GitHubSsoHeaders.logPartialResults(headers);

            assertThat(logs.getDebugLogs()).isEmpty();
        }
    }
}
