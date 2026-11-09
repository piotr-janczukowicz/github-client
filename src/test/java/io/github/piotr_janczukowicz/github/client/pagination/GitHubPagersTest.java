package io.github.piotr_janczukowicz.github.client.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubPagersTest {

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubPagers> constructor = GitHubPagers.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void searchCapReached_shouldUsePageOne_whenPageIsOne() {
        assertSoftly(softly -> {
            softly.assertThat(GitHubPagers.isSearchCapReached(1, 100, 999)).isFalse();
            softly.assertThat(GitHubPagers.isSearchCapReached(1, 100, 1000)).isTrue();
        });
    }

    @Test
    void searchCapReached_shouldUseGivenPage_whenPageIsGreaterThanOne() {
        assertSoftly(softly -> {
            softly.assertThat(GitHubPagers.isSearchCapReached(10, 100, 100)).isTrue();
            softly.assertThat(GitHubPagers.isSearchCapReached(9, 100, 100)).isFalse();
        });
    }

    @Test
    void searchCapReached_shouldUseItemsReceived_whenBoundPerPageExceedsActualPage() {
        assertSoftly(softly -> {
            softly.assertThat(GitHubPagers.isSearchCapReached(1, 100, 30)).isFalse();
            softly.assertThat(GitHubPagers.isSearchCapReached(1, 100, 300)).isFalse();
            softly.assertThat(GitHubPagers.isSearchCapReached(1, 100, 1000)).isTrue();
        });
    }
}
