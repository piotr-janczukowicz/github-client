package io.github.piotr_janczukowicz.github.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class GitHubPageRequestKindValidatorsTest {

    @ParameterizedTest
    @MethodSource("supportedRequests")
    void requireAllowedPageRequestKind_shouldReturn_whenKindAllowed(
            final GitHubPageRequest request, final Set<GitHubPageRequestKind> allowed) {
        final Throwable thrown =
                catchThrowable(() -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(request, allowed));

        assertThat(thrown).isNull();
    }

    @ParameterizedTest
    @MethodSource("noneAlwaysAllowed")
    void requireAllowedPageRequestKind_shouldReturn_whenGitHubNoneRequest(final Set<GitHubPageRequestKind> allowed) {
        final Throwable thrown = catchThrowable(
                () -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(GitHubPageRequest.none(), allowed));

        assertThat(thrown).isNull();
    }

    @ParameterizedTest
    @MethodSource("unsupportedRequests")
    void requireAllowedPageRequestKind_shouldThrow_whenKindNotAllowed(
            final GitHubPageRequest request, final Set<GitHubPageRequestKind> allowed, final String actualLabel) {
        final Throwable thrown =
                catchThrowable(() -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(request, allowed));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported pagination request: " + actualLabel)
                .hasMessageContaining("allowed: none,");
    }

    @Test
    void requireAllowedPageRequestKind_shouldFormatCustomAllowedSet_whenNotPredefinedConstant() {
        final Set<GitHubPageRequestKind> allowed = EnumSet.of(GitHubPageRequestKind.PAGE, GitHubPageRequestKind.SINCE);

        final Throwable thrown = catchThrowable(() ->
                GitHubPageRequestKindValidators.requireAllowedPageRequestKind(GitHubPageRequest.byCursor(), allowed));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported pagination request: cursor")
                .hasMessageContaining("allowed: none, page, since");
    }

    @Test
    void requireAllowedPageRequestKind_shouldFormatPageOrCursorLabel_whenPredefinedConstant() {
        final Throwable thrown = catchThrowable(() -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(
                GitHubPageRequest.bySince(), GitHubPageRequestKindValidators.PAGE_OR_CURSOR));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported pagination request: since")
                .hasMessageContaining("allowed: none, page, cursor");
    }

    @ParameterizedTest
    @MethodSource("nullRequireArgs")
    void requireAllowedPageRequestKind_shouldThrow_whenRequiredArgNull(final RequireCall call, final String message) {
        final Throwable thrown = catchThrowable(call::run);

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining(message);
    }

    @Test
    void requireAllowedPageRequestKind_shouldFormatDeliveryCursorLabel_whenPredefinedConstant() {
        final Throwable thrown = catchThrowable(() -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(
                GitHubPageRequest.byPage(), GitHubPageRequestKindValidators.DELIVERY_CURSOR));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported pagination request: page")
                .hasMessageContaining("allowed: none, delivery cursor");
    }

    @Test
    void requireNoExplicitPerPage_shouldThrow_whenExplicitPerPage() {
        final Throwable thrown = catchThrowable(() -> GitHubPageRequestKindValidators.requireNoExplicitPerPage(
                GitHubSinceRequest.of(1L, 30), GitHubPageRequestKindValidators.SINCE));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported pagination request: explicit per_page")
                .hasMessageContaining("allowed: none, since");
    }

    @Test
    void requireNoExplicitPerPage_shouldReturn_whenSinceWithoutPerPage() {
        final Throwable thrown = catchThrowable(() -> GitHubPageRequestKindValidators.requireNoExplicitPerPage(
                GitHubSinceRequest.of(1L), GitHubPageRequestKindValidators.SINCE));

        assertThat(thrown).isNull();
    }

    @Test
    void requireNoExplicitPerPage_shouldReturn_whenGitHubNoneRequest() {
        final Throwable thrown = catchThrowable(() -> GitHubPageRequestKindValidators.requireNoExplicitPerPage(
                GitHubPageRequest.none(), GitHubPageRequestKindValidators.SINCE));

        assertThat(thrown).isNull();
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubPageRequestKindValidators> constructor =
                GitHubPageRequestKindValidators.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    private static Stream<Arguments> supportedRequests() {
        return Stream.of(
                Arguments.of(GitHubPageRequest.byPage(), GitHubPageRequestKindValidators.PAGE),
                Arguments.of(GitHubPageRequest.bySince(), GitHubPageRequestKindValidators.SINCE),
                Arguments.of(GitHubPageRequest.byCursor(), GitHubPageRequestKindValidators.CURSOR),
                Arguments.of(GitHubPageRequest.byDeliveryCursor(), GitHubPageRequestKindValidators.DELIVERY_CURSOR),
                Arguments.of(GitHubPageRequest.byPage(), GitHubPageRequestKindValidators.PAGE_OR_CURSOR),
                Arguments.of(GitHubPageRequest.byCursor(), GitHubPageRequestKindValidators.PAGE_OR_CURSOR),
                Arguments.of(GitHubPageRequest.byStartIndex(), GitHubPageRequestKindValidators.START_INDEX));
    }

    private static Stream<Set<GitHubPageRequestKind>> noneAlwaysAllowed() {
        return Stream.of(
                GitHubPageRequestKindValidators.PAGE,
                GitHubPageRequestKindValidators.SINCE,
                GitHubPageRequestKindValidators.CURSOR,
                GitHubPageRequestKindValidators.DELIVERY_CURSOR,
                GitHubPageRequestKindValidators.START_INDEX,
                GitHubPageRequestKindValidators.PAGE_OR_CURSOR);
    }

    private static Stream<Arguments> unsupportedRequests() {
        return Stream.of(
                Arguments.of(GitHubPageRequest.byPage(), GitHubPageRequestKindValidators.SINCE, "page"),
                Arguments.of(GitHubPageRequest.bySince(), GitHubPageRequestKindValidators.PAGE, "since"),
                Arguments.of(GitHubPageRequest.byCursor(), GitHubPageRequestKindValidators.PAGE, "cursor"),
                Arguments.of(
                        GitHubPageRequest.byDeliveryCursor(), GitHubPageRequestKindValidators.PAGE, "delivery cursor"),
                Arguments.of(GitHubPageRequest.byPage(), GitHubPageRequestKindValidators.CURSOR, "page"),
                Arguments.of(GitHubPageRequest.bySince(), GitHubPageRequestKindValidators.PAGE_OR_CURSOR, "since"),
                Arguments.of(GitHubPageRequest.byStartIndex(), GitHubPageRequestKindValidators.PAGE, "start index"),
                Arguments.of(GitHubPageRequest.byPage(), GitHubPageRequestKindValidators.START_INDEX, "page"));
    }

    @Test
    void requireAllowedPageRequestKind_shouldFormatStartIndexLabel_whenPredefinedConstant() {
        final Throwable thrown = catchThrowable(() -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(
                GitHubPageRequest.byCursor(), GitHubPageRequestKindValidators.START_INDEX));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported pagination request: cursor")
                .hasMessageContaining("allowed: none, start index");
    }

    private static Stream<Arguments> nullRequireArgs() {
        return Stream.of(
                Arguments.of(
                        (RequireCall) () -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(
                                null, GitHubPageRequestKindValidators.PAGE),
                        "request"),
                Arguments.of(
                        (RequireCall) () -> GitHubPageRequestKindValidators.requireAllowedPageRequestKind(
                                GitHubPageRequest.none(), null),
                        "allowed"));
    }

    @FunctionalInterface
    interface RequireCall {
        void run();
    }
}
