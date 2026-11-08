package io.github.piotr_janczukowicz.github.client.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;

import io.github.piotr_janczukowicz.github.pagination.GitHubCursorRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubDeliveryCursorRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageNumberRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubSinceRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubStartIndexRequest;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class GitHubPageQueriesTest {

    @Test
    void queryValues_shouldOmitPagination_whenGitHubNoneRequest() {
        final Map<String, List<String>> query =
                GitHubPageQueries.queryValues(GitHubPageRequest.none(), Map.of("type", List.of("User")), 30, true);

        assertThat(query).containsExactly(entry("type", List.of("User")));
    }

    @Test
    void queryValues_shouldOmitPerPage_whenSendPerPageFalse() {
        final Map<String, List<String>> query = GitHubPageQueries.queryValues(
                GitHubPageNumberRequest.of(3, 10), Map.of("type", List.of("User")), 10, false);

        assertThat(query).containsExactly(entry("type", List.of("User")), entry("page", List.of("3")));
    }

    @Test
    void queryValues_shouldIncludeTokenThenPerPage_whenSincePresent() {
        final Map<String, List<String>> query =
                GitHubPageQueries.queryValues(GitHubSinceRequest.of(9L, 5), Map.of("type", List.of("User")), 5, true);

        assertThat(query)
                .containsExactly(
                        entry("type", List.of("User")), entry("since", List.of("9")), entry("per_page", List.of("5")));
    }

    @Test
    void queryValues_shouldIncludeTokenThenPerPage_whenCursorPresent() {
        final Map<String, List<String>> query = GitHubPageQueries.queryValues(
                GitHubCursorRequest.after("tok", 5), Map.of("type", List.of("User")), 5, true);

        assertThat(query)
                .containsExactly(
                        entry("type", List.of("User")),
                        entry("after", List.of("tok")),
                        entry("per_page", List.of("5")));
    }

    @Test
    void queryValues_shouldIncludeCursorThenPerPage_whenDeliveryCursorPresent() {
        final Map<String, List<String>> query = GitHubPageQueries.queryValues(
                GitHubDeliveryCursorRequest.of("tok", 5), Map.of("type", List.of("User")), 5, true);

        assertThat(query)
                .containsExactly(
                        entry("type", List.of("User")),
                        entry("cursor", List.of("tok")),
                        entry("per_page", List.of("5")));
    }

    @Test
    void queryValues_shouldIncludeStartIndexAndCountWithoutPerPage_whenStartIndexPresent() {
        final Map<String, List<String>> query = GitHubPageQueries.queryValues(
                GitHubStartIndexRequest.of(3, 2), Map.of("type", List.of("User")), 2, false);

        assertThat(query)
                .containsExactly(
                        entry("type", List.of("User")),
                        entry("startIndex", List.of("3")),
                        entry("count", List.of("2")));
    }

    @ParameterizedTest
    @MethodSource("requestsForNullBaseQuery")
    void queryValues_shouldThrow_whenBaseQueryNull(final GitHubPageRequest request) {
        final Throwable thrown = catchThrowable(() -> GitHubPageQueries.queryValues(request, null, 30, true));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("baseQuery");
    }

    @ParameterizedTest
    @MethodSource("requestsForNullBoundPerPage")
    void boundPerPage_shouldThrow_whenConfigNull(final GitHubPageRequest request) {
        final Throwable thrown = catchThrowable(() -> GitHubPageQueries.boundPerPage(request, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("config");
    }

    @Test
    void boundPerPage_shouldReturnConfigValue_whenGitHubNoneRequest() {
        final PaginationConfiguration config = PaginationConfiguration.defaults();

        final int perPage = GitHubPageQueries.boundPerPage(GitHubPageRequest.none(), config);

        assertThat(perPage).isEqualTo(config.perPage());
    }

    @Test
    void boundPerPage_shouldReturnConfigValue_whenExplicitPerPageOmitted() {
        final PaginationConfiguration config =
                PaginationConfiguration.builder().perPage(40).build();

        final int perPage = GitHubPageQueries.boundPerPage(GitHubPageRequest.bySince(), config);

        assertThat(perPage).isEqualTo(40);
    }

    @Test
    void boundPerPage_shouldReturnExplicitValue_whenWithinConfig() {
        final PaginationConfiguration config = PaginationConfiguration.defaults();

        final int perPage = GitHubPageQueries.boundPerPage(GitHubSinceRequest.first(10), config);

        assertThat(perPage).isEqualTo(10);
    }

    @Test
    void boundPerPage_shouldThrow_whenExplicitExceedsEndpointMax() {
        final PaginationConfiguration config = PaginationConfiguration.defaults();

        final Throwable thrown =
                catchThrowable(() -> GitHubPageQueries.boundPerPage(GitHubSinceRequest.first(101), config));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
    }

    @Test
    void boundPerPage_shouldThrow_whenExplicitExceedsMaxPerPage() {
        final PaginationConfiguration config = PaginationConfiguration.defaults();

        final Throwable thrown =
                catchThrowable(() -> GitHubPageQueries.boundPerPage(GitHubSinceRequest.first(50), config, 30));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("30");
    }

    @Test
    void queryValues_shouldThrow_whenRequestNull() {
        final Throwable thrown = catchThrowable(() -> GitHubPageQueries.queryValues(null, Map.of(), 30, true));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
    }

    private static Stream<GitHubPageRequest> requestsForNullBaseQuery() {
        return Stream.of(
                GitHubPageRequest.none(),
                GitHubPageNumberRequest.of(1, 10),
                GitHubPageRequest.byCursor(),
                GitHubPageRequest.byDeliveryCursor());
    }

    private static Stream<GitHubPageRequest> requestsForNullBoundPerPage() {
        return Stream.of(
                GitHubPageRequest.none(),
                GitHubPageRequest.bySince(),
                GitHubPageRequest.byCursor(),
                GitHubPageRequest.byDeliveryCursor());
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubPageQueries> constructor = GitHubPageQueries.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
