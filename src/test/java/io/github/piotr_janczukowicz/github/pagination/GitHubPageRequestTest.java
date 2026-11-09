package io.github.piotr_janczukowicz.github.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class GitHubPageRequestTest {

    @Test
    void none_shouldOmitPaginationQuery_whenCreated() {
        final GitHubPageRequest request = GitHubPageRequest.none();

        assertThat(request.explicitPerPage()).isEmpty();
    }

    @Test
    void putQueryParameters_shouldWriteNothing_whenGitHubNoneRequest() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubPageRequest.none().putQueryParameters(query);

        assertThat(query).isEmpty();
    }

    @Test
    void byPage_shouldCreateFirstPage_whenPerPageOmitted() {
        final GitHubPageNumberRequest request = (GitHubPageNumberRequest) GitHubPageRequest.byPage();

        assertSoftly(softly -> {
            softly.assertThat(request.page()).isEqualTo(1);
            softly.assertThat(request.explicitPerPage()).isEmpty();
        });
    }

    @Test
    void of_shouldCreatePageRequest_whenPageAndPerPageProvided() {
        final GitHubPageNumberRequest request = GitHubPageNumberRequest.of(2, 50);

        assertSoftly(softly -> {
            softly.assertThat(request.page()).isEqualTo(2);
            softly.assertThat(request.explicitPerPage()).hasValue(50);
        });
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void factories_shouldThrow_whenPageBelowOne(final ThrowingCallable call) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("page");
    }

    @ParameterizedTest
    @MethodSource("invalidPerPages")
    void factories_shouldThrow_whenPerPageBelowOne(final ThrowingCallable call) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perPage");
    }

    @Test
    void of_shouldCreateSinceRequest_whenSinceAndPerPageProvided() {
        final GitHubSinceRequest request = GitHubSinceRequest.of(100L, 20);

        assertSoftly(softly -> {
            softly.assertThat(request.since()).isEqualTo(100L);
            softly.assertThat(request.explicitPerPage()).hasValue(20);
        });
    }

    @Test
    void first_shouldCreateSinceRequest_whenOnlyPerPageProvided() {
        final GitHubSinceRequest request = GitHubSinceRequest.first(15);

        assertSoftly(softly -> {
            softly.assertThat(request.since()).isNull();
            softly.assertThat(request.explicitPerPage()).hasValue(15);
        });
    }

    @Test
    void bySince_shouldCreateEmptySince_whenNoArgs() {
        final GitHubSinceRequest request = (GitHubSinceRequest) GitHubPageRequest.bySince();

        assertSoftly(softly -> {
            softly.assertThat(request.since()).isNull();
            softly.assertThat(request.explicitPerPage()).isEmpty();
        });
    }

    @Test
    void putQueryParameters_shouldWritePage_whenPageRequest() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubPageNumberRequest.of(3, 10).putQueryParameters(query);

        assertThat(query).containsExactly(entry("page", "3"));
    }

    @Test
    void putQueryParameters_shouldWriteSince_whenSincePresent() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubSinceRequest.of(9L).putQueryParameters(query);

        assertThat(query).containsExactly(entry("since", "9"));
    }

    @Test
    void putQueryParameters_shouldWriteNothing_whenSinceOmitted() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubPageRequest.bySince().putQueryParameters(query);

        assertThat(query).isEmpty();
    }

    @Test
    void of_shouldCreateUnpositionedCursor_whenPerPageProvided() {
        final GitHubCursorRequest request = GitHubCursorRequest.of(10);

        assertSoftly(softly -> {
            softly.assertThat(request.beforeCursor()).isEmpty();
            softly.assertThat(request.afterCursor()).isEmpty();
            softly.assertThat(request.explicitPerPage()).hasValue(10);
        });
    }

    @Test
    void unpositioned_shouldOmitCursorsAndPerPage_whenCreated() {
        final GitHubCursorRequest request = GitHubCursorRequest.unpositioned();

        assertSoftly(softly -> {
            softly.assertThat(request.beforeCursor()).isEmpty();
            softly.assertThat(request.afterCursor()).isEmpty();
            softly.assertThat(request.explicitPerPage()).isEmpty();
        });
    }

    @Test
    void of_shouldCreateUnpositionedDeliveryCursor_whenPerPageProvided() {
        final GitHubDeliveryCursorRequest request = GitHubDeliveryCursorRequest.of(10);

        assertSoftly(softly -> {
            softly.assertThat(request.cursorValue()).isEmpty();
            softly.assertThat(request.explicitPerPage()).hasValue(10);
        });
    }

    @Test
    void after_shouldWriteAfter_whenCursorPresent() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubCursorRequest.after("token").putQueryParameters(query);

        assertThat(query).containsExactly(entry("after", "token"));
    }

    @Test
    void before_shouldWriteBefore_whenCursorPresent() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubCursorRequest.before("token").putQueryParameters(query);

        assertThat(query).containsExactly(entry("before", "token"));
    }

    @Test
    void before_shouldCreateRequest_whenBeforeAndPerPageProvided() {
        final GitHubCursorRequest request = GitHubCursorRequest.before("token", 15);

        assertSoftly(softly -> {
            softly.assertThat(request.beforeCursor()).hasValue("token");
            softly.assertThat(request.afterCursor()).isEmpty();
            softly.assertThat(request.explicitPerPage()).hasValue(15);
        });
    }

    @Test
    void constructor_shouldThrow_whenBeforeAndAfterBothPresent() {
        final Throwable thrown = catchThrowable(() -> new GitHubCursorRequest("b", "a", null));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before and after must have at most one value present");
    }

    @Test
    void putQueryParameters_shouldWriteNothing_whenCursorOmitted() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubPageRequest.byCursor().putQueryParameters(query);

        assertThat(query).isEmpty();
    }

    @Test
    void putQueryParameters_shouldWriteCursor_whenDeliveryCursorPresent() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubDeliveryCursorRequest.of("next").putQueryParameters(query);

        assertThat(query).containsExactly(entry("cursor", "next"));
    }

    @Test
    void putQueryParameters_shouldWriteNothing_whenDeliveryCursorOmitted() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubPageRequest.byDeliveryCursor().putQueryParameters(query);

        assertThat(query).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidCursorStrings")
    void factories_shouldThrow_whenCursorBlank(final ThrowingCallable call) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("nullCursorStrings")
    void factories_shouldThrow_whenCursorNull(final ThrowingCallable call, final String message) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining(message);
    }

    @Test
    void byStartIndex_shouldStartAtOneWithoutCount_whenCalled() {
        final GitHubPageRequest request = GitHubPageRequest.byStartIndex();

        assertSoftly(softly -> {
            softly.assertThat(request).isEqualTo(GitHubStartIndexRequest.first());
            softly.assertThat(request.explicitPerPage()).isEmpty();
        });
    }

    @Test
    void of_shouldCreateIndexWithCount_whenStartIndexAndCountProvided() {
        final GitHubStartIndexRequest request = GitHubStartIndexRequest.of(21, 50);

        assertSoftly(softly -> {
            softly.assertThat(request.startIndex()).isEqualTo(21);
            softly.assertThat(request.count()).isEqualTo(50);
            softly.assertThat(request.explicitPerPage()).isEmpty();
        });
    }

    @Test
    void putQueryParameters_shouldWriteStartIndexAndCount_whenBothPresent() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubStartIndexRequest.of(3, 2).putQueryParameters(query);

        assertThat(query).containsExactly(entry("startIndex", "3"), entry("count", "2"));
    }

    @Test
    void putQueryParameters_shouldWriteStartIndexOnly_whenCountOmitted() {
        final Map<String, String> query = new LinkedHashMap<>();

        GitHubStartIndexRequest.of(4).putQueryParameters(query);

        assertThat(query).containsExactly(entry("startIndex", "4"));
    }

    @Test
    void next_shouldKeepCount_whenFollowingPageFound() {
        final Optional<GitHubStartIndexRequest> following =
                GitHubStartIndexRequest.of(1, 2).next(2, 5);

        assertThat(following).contains(GitHubStartIndexRequest.of(3, 2));
    }

    @ParameterizedTest
    @MethodSource("nextCases")
    void next_shouldReturnFollowingStartIndexOrEmpty_whenBodyDescribesResults(
            final GitHubStartIndexRequest current,
            final int itemsReceived,
            final Integer totalResults,
            final Optional<Integer> expectedStartIndex) {
        final Optional<Integer> actual =
                current.next(itemsReceived, totalResults).map(GitHubStartIndexRequest::startIndex);

        assertThat(actual).isEqualTo(expectedStartIndex);
    }

    @ParameterizedTest
    @MethodSource("invalidStartIndexRequests")
    void factories_shouldThrow_whenStartIndexOrCountOutOfRange(final ThrowingCallable call, final String parameter) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(parameter);
    }

    private static Stream<Arguments> nextCases() {
        return Stream.of(
                Arguments.of(GitHubStartIndexRequest.of(1, 2), 2, 5, Optional.of(3)),
                Arguments.of(GitHubStartIndexRequest.of(1, 4), 4, 5, Optional.of(5)),
                Arguments.of(GitHubStartIndexRequest.first(), 5, 5, Optional.empty()),
                Arguments.of(GitHubStartIndexRequest.first(), 0, 5, Optional.empty()),
                Arguments.of(GitHubStartIndexRequest.first(), 2, null, Optional.empty()),
                Arguments.of(GitHubStartIndexRequest.of(1, 2), 5, 10, Optional.of(6)),
                Arguments.of(GitHubStartIndexRequest.of(1, 2), 1, 5, Optional.of(2)),
                Arguments.of(GitHubStartIndexRequest.first(), 1, -1, Optional.empty()),
                Arguments.of(GitHubStartIndexRequest.of(Integer.MAX_VALUE), 1, Integer.MAX_VALUE, Optional.empty()),
                Arguments.of(
                        GitHubStartIndexRequest.of(Integer.MAX_VALUE - 1),
                        1,
                        Integer.MAX_VALUE,
                        Optional.of(Integer.MAX_VALUE)));
    }

    private static Stream<Arguments> invalidStartIndexRequests() {
        return Stream.of(
                Arguments.of((ThrowingCallable) () -> new GitHubStartIndexRequest(0, null), "startIndex"),
                Arguments.of((ThrowingCallable) () -> GitHubStartIndexRequest.of(0), "startIndex"),
                Arguments.of((ThrowingCallable) () -> GitHubStartIndexRequest.of(0, 10), "startIndex"),
                Arguments.of((ThrowingCallable) () -> GitHubStartIndexRequest.of(1, 0), "count"),
                Arguments.of((ThrowingCallable) () -> GitHubStartIndexRequest.of(1, 101), "count"),
                Arguments.of((ThrowingCallable) () -> new GitHubStartIndexRequest(1, 101), "count"));
    }

    @Test
    void cursorFactories_shouldNameTheFieldOnce_whenCursorIsBlank() {
        final Throwable after = catchThrowable(() -> GitHubCursorRequest.after(" "));
        final Throwable before = catchThrowable(() -> GitHubCursorRequest.before(" "));
        final Throwable delivery = catchThrowable(() -> GitHubDeliveryCursorRequest.of(" "));

        assertSoftly(softly -> {
            softly.assertThat(after).hasMessage("after must not be blank");
            softly.assertThat(before).hasMessage("before must not be blank");
            softly.assertThat(delivery).hasMessage("cursor must not be blank");
        });
    }

    private static Stream<ThrowingCallable> invalidPages() {
        return Stream.of(() -> new GitHubPageNumberRequest(0, null), () -> GitHubPageNumberRequest.of(0, 10));
    }

    private static Stream<ThrowingCallable> invalidPerPages() {
        return Stream.of(
                () -> new GitHubPageNumberRequest(1, 0),
                () -> GitHubPageNumberRequest.of(1, 0),
                () -> new GitHubSinceRequest(null, 0),
                () -> GitHubSinceRequest.first(0),
                () -> new GitHubCursorRequest(null, null, 0),
                () -> GitHubCursorRequest.of(0),
                () -> GitHubCursorRequest.after("a", 0),
                () -> GitHubCursorRequest.before("b", 0),
                () -> new GitHubDeliveryCursorRequest(null, 0),
                () -> GitHubDeliveryCursorRequest.of(0),
                () -> GitHubDeliveryCursorRequest.of("a", 0));
    }

    private static Stream<ThrowingCallable> invalidCursorStrings() {
        return Stream.of(
                () -> GitHubCursorRequest.after(""),
                () -> GitHubCursorRequest.after("  "),
                () -> GitHubCursorRequest.before(""),
                () -> GitHubCursorRequest.before("  "),
                () -> GitHubDeliveryCursorRequest.of(""),
                () -> GitHubDeliveryCursorRequest.of("  "));
    }

    private static Stream<Arguments> nullCursorStrings() {
        return Stream.of(
                Arguments.of((ThrowingCallable) () -> GitHubCursorRequest.after(null), "after"),
                Arguments.of((ThrowingCallable) () -> GitHubCursorRequest.before(null), "before"),
                Arguments.of((ThrowingCallable) () -> GitHubDeliveryCursorRequest.of(null), "cursor"));
    }
}
