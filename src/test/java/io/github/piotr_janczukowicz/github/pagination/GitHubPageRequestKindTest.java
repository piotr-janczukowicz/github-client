package io.github.piotr_janczukowicz.github.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class GitHubPageRequestKindTest {

    @ParameterizedTest
    @MethodSource("kinds")
    void of_shouldReturnKind_whenRequestProvided(final GitHubPageRequest request, final GitHubPageRequestKind kind) {
        assertThat(GitHubPageRequestKind.of(request)).isEqualTo(kind);
    }

    @ParameterizedTest
    @MethodSource("labels")
    void label_shouldMatchKind(final GitHubPageRequestKind kind, final String label) {
        assertThat(kind.label()).isEqualTo(label);
    }

    @Test
    void of_shouldThrow_whenRequestNull() {
        final Throwable thrown = catchThrowable(() -> GitHubPageRequestKind.of(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("request");
    }

    private static Stream<Arguments> kinds() {
        return Stream.of(
                Arguments.of(GitHubPageRequest.none(), GitHubPageRequestKind.NONE),
                Arguments.of(GitHubPageRequest.byPage(), GitHubPageRequestKind.PAGE),
                Arguments.of(GitHubPageRequest.bySince(), GitHubPageRequestKind.SINCE),
                Arguments.of(GitHubPageRequest.byCursor(), GitHubPageRequestKind.CURSOR),
                Arguments.of(GitHubPageRequest.byDeliveryCursor(), GitHubPageRequestKind.DELIVERY_CURSOR),
                Arguments.of(GitHubPageRequest.byStartIndex(), GitHubPageRequestKind.START_INDEX));
    }

    private static Stream<Arguments> labels() {
        return Stream.of(
                Arguments.of(GitHubPageRequestKind.NONE, "none"),
                Arguments.of(GitHubPageRequestKind.PAGE, "page"),
                Arguments.of(GitHubPageRequestKind.SINCE, "since"),
                Arguments.of(GitHubPageRequestKind.CURSOR, "cursor"),
                Arguments.of(GitHubPageRequestKind.DELIVERY_CURSOR, "delivery cursor"),
                Arguments.of(GitHubPageRequestKind.START_INDEX, "start index"));
    }
}
