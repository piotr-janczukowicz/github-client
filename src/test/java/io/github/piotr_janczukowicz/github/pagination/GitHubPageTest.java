package io.github.piotr_janczukowicz.github.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@UnitTest
class GitHubPageTest {

    @ParameterizedTest
    @EnumSource(ConstructorNullArg.class)
    void constructor_shouldThrow_whenRequiredArgumentNull(final ConstructorNullArg argument) {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final Throwable thrown = catchThrowable(() -> constructWithNull(argument, session));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining(argument.parameterName());
    }

    @Test
    void constructor_shouldLeaveTotalCountEmpty_whenThreeArgConstructor() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final GitHubPage<Object> page = new GitHubPage<>(List.of("a"), session, 1);

        assertThat(page.items()).containsExactly("a");
        assertThat(page.totalCount()).isNull();
    }

    @Test
    void constructor_shouldCopyItems_whenCallerListChangesLater() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);
        final List<Object> items = new ArrayList<>(List.of("a"));

        final GitHubPage<Object> page = new GitHubPage<>(items, session, 1);
        items.add("b");

        assertThat(page.items()).containsExactly("a");
    }

    @Test
    void constructor_shouldThrow_whenItemsContainNull() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);
        final List<Object> items = new ArrayList<>();
        items.add(null);

        final Throwable thrown = catchThrowable(() -> new GitHubPage<>(items, session, 1));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_shouldThrow_whenPageCountBelowOne() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final Throwable thrown = catchThrowable(() -> new GitHubPage<>(List.of(), session, 0));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pageCount");
    }

    @Test
    void constructor_shouldExposeEnvelope_whenSearchPage() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);
        final GitHubSearchEnvelope<Object> envelope = new GitHubSearchEnvelope<>(2, true, List.of("a"), null, null);

        final GitHubPage<Object> page = new GitHubPage<>(envelope, session, 1);

        assertThat(page.items()).containsExactly("a");
        assertThat(page.totalCount()).isEqualTo(2);
        assertThat(page.areResultsIncomplete()).isTrue();
        assertThat(page.envelope()).isEqualTo(envelope);
    }

    @Test
    void constructor_shouldThrow_whenEnvelopeNull() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final Throwable thrown =
                catchThrowable(() -> new GitHubPage<>((GitHubSearchEnvelope<Object>) null, session, 1));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("envelope");
    }

    @Test
    void searchAccessors_shouldBeEmpty_whenListPage() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final GitHubPage<Object> page = new GitHubPage<>(List.of("a"), session, 1);

        assertThat(page.areResultsIncomplete()).isFalse();
        assertThat(page.searchType()).isNull();
        assertThat(page.lexicalFallbackReasons()).isEmpty();
        assertThat(page.envelope()).isNull();
    }

    @Test
    void taskCounts_shouldBeExposed_whenTaskListPage() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final GitHubPage<Object> page = new GitHubPage<>(List.of("a"), session, 1, null, new GitHubTaskCounts(4, 2));

        assertThat(page.totalActiveCount()).isEqualTo(4);
        assertThat(page.totalArchivedCount()).isEqualTo(2);
        assertThat(page.totalCount()).isNull();
    }

    @Test
    void taskCounts_shouldBeEmpty_whenListPageHasNone() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final GitHubPage<Object> plain = new GitHubPage<>(List.of("a"), session, 1);
        final GitHubPage<Object> withoutCounts =
                new GitHubPage<>(List.of("a"), session, 1, null, new GitHubTaskCounts(null, null));

        assertThat(plain.totalActiveCount()).isNull();
        assertThat(plain.totalArchivedCount()).isNull();
        assertThat(withoutCounts.totalActiveCount()).isNull();
        assertThat(withoutCounts.totalArchivedCount()).isNull();
    }

    @Test
    void constructor_shouldThrow_whenTaskCountsNull() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);

        final Throwable thrown = catchThrowable(() -> new GitHubPage<>(List.of("a"), session, 1, null, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("taskCounts");
    }

    @Test
    void taskCounts_shouldBeEmpty_whenSearchPage() {
        @SuppressWarnings("unchecked")
        final GitHubPageWalk<Object> session = mock(GitHubPageWalk.class);
        final GitHubSearchEnvelope<Object> envelope = new GitHubSearchEnvelope<>(3, false, List.of("a"), null, null);

        final GitHubPage<Object> page = new GitHubPage<>(envelope, session, 1);

        assertThat(page.totalActiveCount()).isNull();
        assertThat(page.totalArchivedCount()).isNull();
    }

    private static void constructWithNull(final ConstructorNullArg argument, final GitHubPageWalk<Object> session) {
        switch (argument) {
            case ITEMS -> new GitHubPage<>((List<Object>) null, session, 1);
            case SESSION -> new GitHubPage<>(List.of(), null, 1);
        }
    }

    enum ConstructorNullArg {
        ITEMS("items"),
        SESSION("session");

        private final String parameterName;

        ConstructorNullArg(final String parameterName) {
            this.parameterName = parameterName;
        }

        String parameterName() {
            return this.parameterName;
        }
    }
}
