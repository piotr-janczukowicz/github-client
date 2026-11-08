package io.github.piotr_janczukowicz.github.client.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubInstallationScopeTest {

    @Test
    void toRequestBody_shouldReturnEmptyBody_whenScopeIsAll() {
        final Map<String, Object> body = GitHubInstallationScope.ALL.toRequestBody();

        assertThat(body).isEmpty();
    }

    @Test
    void toRequestBody_shouldIncludeEveryNarrowing_whenAllAreSet() {
        final GitHubInstallationScope scope = GitHubInstallationScope.ALL
                .withRepositories(List.of("repo-a", "repo-b"))
                .withRepositoryIds(List.of(5L))
                .withPermissions(Map.of("issues", "write", "contents", "read"));

        final Map<String, Object> body = scope.toRequestBody();

        assertSoftly(softly -> {
            softly.assertThat(body.get("repositories")).isEqualTo(List.of("repo-a", "repo-b"));
            softly.assertThat(body.get("repository_ids")).isEqualTo(List.of(5L));
            softly.assertThat(body.get("permissions")).isEqualTo(Map.of("contents", "read", "issues", "write"));
        });
    }

    @Test
    void withRepositories_shouldThrow_whenNamesNull() {
        final Throwable thrown = catchThrowable(() -> GitHubInstallationScope.ALL.withRepositories(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("repositoryNames");
    }

    @Test
    void withRepositories_shouldThrow_whenNamesEmpty() {
        final List<String> names = List.of();

        final Throwable thrown = catchThrowable(() -> GitHubInstallationScope.ALL.withRepositories(names));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("repositoryNames must not be empty");
    }

    @Test
    void withRepositoryIds_shouldThrow_whenIdNotPositive() {
        final List<Long> ids = List.of(0L);

        final Throwable thrown = catchThrowable(() -> GitHubInstallationScope.ALL.withRepositoryIds(ids));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("repository id must be positive");
    }

    @Test
    void withRepositories_shouldAcceptMaximum_whenNamesAreAtTheLimit() {
        final List<String> names = repositoryNames(500);

        final GitHubInstallationScope scope = GitHubInstallationScope.ALL.withRepositories(names);

        assertThat(scope.repositories()).hasSize(500);
    }

    @Test
    void withRepositories_shouldThrow_whenMoreThanMaximumNames() {
        final List<String> names = repositoryNames(501);

        final Throwable thrown = catchThrowable(() -> GitHubInstallationScope.ALL.withRepositories(names));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GitHub allows at most 500 repositories");
    }

    @Test
    void withRepositoryIds_shouldThrow_whenMoreThanMaximumIds() {
        final List<Long> ids = LongStream.rangeClosed(1, 501).boxed().toList();

        final Throwable thrown = catchThrowable(() -> GitHubInstallationScope.ALL.withRepositoryIds(ids));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GitHub allows at most 500 repositories");
    }

    @Test
    void withRepositories_shouldThrow_whenNameContainsSlash() {
        final List<String> names = List.of("owner/repo-a");

        final Throwable thrown = catchThrowable(() -> GitHubInstallationScope.ALL.withRepositories(names));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("repository name must not contain '/', give the name without the owner");
    }

    @Test
    void withPermissions_shouldThrow_whenLevelBlank() {
        final Map<String, String> levels = Map.of("issues", " ");

        final Throwable thrown = catchThrowable(() -> GitHubInstallationScope.ALL.withPermissions(levels));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("permission level must not be blank");
    }

    private static List<String> repositoryNames(final int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> "repo-" + index)
                .toList();
    }
}
