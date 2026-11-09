package io.github.piotr_janczukowicz.github.client.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubPageBodyTest {

    @Test
    void of_shouldLeaveTotalCountEmpty_whenCountOmitted() {
        final GitHubPageBody<String> body = GitHubPageBody.of(List.of("a"));

        assertSoftly(softly -> {
            softly.assertThat(body.items()).containsExactly("a");
            softly.assertThat(body.totalCount()).isNull();
        });
    }

    @Test
    void of_shouldRecordTotalCount_whenCountProvided() {
        final GitHubPageBody<String> body = GitHubPageBody.of(List.of("a"), 9);

        assertThat(body.totalCount()).isEqualTo(9);
    }

    @Test
    void of_shouldThrow_whenItemsNull() {
        final Throwable thrown = catchThrowable(() -> GitHubPageBody.of(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("items");
    }

    @Test
    void constructor_shouldCopyItems_whenListMutatedLater() {
        final List<String> items = new ArrayList<>();
        items.add("a");

        final GitHubPageBody<String> body = GitHubPageBody.of(items);
        items.add("b");

        assertThat(body.items()).containsExactly("a");
    }
}
