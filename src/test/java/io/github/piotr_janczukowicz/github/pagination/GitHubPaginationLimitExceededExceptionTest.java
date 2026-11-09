package io.github.piotr_janczukowicz.github.pagination;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubPaginationLimitExceededExceptionTest {

    @Test
    void constructor_shouldExposeCapAndFetchedPages_whenCreated() {
        final GitHubPaginationLimitExceededException thrown = new GitHubPaginationLimitExceededException(2, 2);

        assertSoftly(softly -> {
            softly.assertThat(thrown.maxPages()).isEqualTo(2);
            softly.assertThat(thrown.pageCount()).isEqualTo(2);
            softly.assertThat(thrown).hasMessageContaining("maxPages").hasMessageContaining("2");
            softly.assertThat(thrown).isInstanceOf(GitHubPaginationException.class);
        });
    }
}
