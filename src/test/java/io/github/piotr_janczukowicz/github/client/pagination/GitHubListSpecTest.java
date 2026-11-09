package io.github.piotr_janczukowicz.github.client.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKindValidators;
import io.github.piotr_janczukowicz.github.pagination.GitHubTaskCounts;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubListSpecTest {

    private static final JsonType<List<String>> STRINGS = JsonTypes.listOf(String.class);

    @Test
    void pages_shouldAllowPageKindAndSendPerPage_whenCalled() {
        final GitHubListSpec<String> spec = GitHubListSpec.pages(STRINGS);

        assertSoftly(softly -> {
            softly.assertThat(spec.allowedKinds()).isEqualTo(GitHubPageRequestKindValidators.PAGE);
            softly.assertThat(spec.sendPerPage()).isTrue();
            softly.assertThat(spec.maxPerPage()).isEqualTo(PaginationConfiguration.MAX_PER_PAGE);
        });
    }

    @Test
    void array_shouldAllowPageOrCursorKinds_whenCalled() {
        assertThat(GitHubListSpec.array(GitHubPageRequestKindValidators.PAGE_OR_CURSOR, STRINGS)
                        .allowedKinds())
                .isEqualTo(GitHubPageRequestKindValidators.PAGE_OR_CURSOR);
    }

    @Test
    void cursor_shouldAllowCursorKind_whenCalled() {
        final GitHubListSpec<String> spec = GitHubListSpec.array(GitHubPageRequestKindValidators.CURSOR, STRINGS);

        assertSoftly(softly -> {
            softly.assertThat(spec.allowedKinds()).isEqualTo(GitHubPageRequestKindValidators.CURSOR);
            softly.assertThat(spec.sendPerPage()).isTrue();
        });
    }

    @Test
    void withoutPerPage_shouldOmitPerPage_whenCalled() {
        assertThat(GitHubListSpec.array(GitHubPageRequestKindValidators.CURSOR, STRINGS)
                        .withoutPerPage()
                        .sendPerPage())
                .isFalse();
    }

    @Test
    void withMaxPerPage_shouldReturnCopyWithCap_whenValueInRange() {
        final GitHubListSpec<String> spec = GitHubListSpec.pages(STRINGS);

        final GitHubListSpec<String> capped = spec.withMaxPerPage(30);

        assertSoftly(softly -> {
            softly.assertThat(spec.maxPerPage()).isEqualTo(PaginationConfiguration.MAX_PER_PAGE);
            softly.assertThat(capped.maxPerPage()).isEqualTo(30);
            softly.assertThat(capped.allowedKinds()).isEqualTo(GitHubPageRequestKindValidators.PAGE);
            softly.assertThat(capped.sendPerPage()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, PaginationConfiguration.MAX_PER_PAGE + 1})
    void withMaxPerPage_shouldThrow_whenValueOutOfRange(final int maxPerPage) {
        final GitHubListSpec<String> spec = GitHubListSpec.pages(STRINGS);

        final Throwable thrown = catchThrowable(() -> spec.withMaxPerPage(maxPerPage));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxPerPage");
    }

    @Test
    void deliveryCursor_shouldAllowDeliveryKind_whenCalled() {
        assertThat(GitHubListSpec.array(GitHubPageRequestKindValidators.DELIVERY_CURSOR, STRINGS)
                        .allowedKinds())
                .isEqualTo(GitHubPageRequestKindValidators.DELIVERY_CURSOR);
    }

    @Test
    void since_shouldAllowSinceKindAndSendPerPage_whenCalled() {
        final GitHubListSpec<String> spec = GitHubListSpec.since(STRINGS);

        assertSoftly(softly -> {
            softly.assertThat(spec.allowedKinds()).isEqualTo(GitHubPageRequestKindValidators.SINCE);
            softly.assertThat(spec.sendPerPage()).isTrue();
        });
    }

    @Test
    void sinceWithoutPerPage_shouldOmitPerPage_whenCalled() {
        assertThat(GitHubListSpec.since(STRINGS).withoutPerPage().sendPerPage()).isFalse();
    }

    @Test
    void wrapper_shouldThrow_whenItemsNull() {
        final Throwable thrown = catchThrowable(
                () -> GitHubListSpec.wrapper(GitHubPageRequestKindValidators.CURSOR, String.class, null, unused -> 1));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("items");
    }

    @Test
    void wrapper_shouldReturnNullTotalCount_whenExtractorReturnsNull() {
        final GitHubListSpec<String> spec = GitHubListSpec.wrapper(
                GitHubPageRequestKindValidators.PAGE, Wrapper.class, Wrapper::items, unused -> null);

        final GitHubPageBody<String> body = decodeWrapper(spec, "{\"items\":[\"a\"],\"total\":3}");

        assertSoftly(softly -> {
            softly.assertThat(body.items()).containsExactly("a");
            softly.assertThat(body.totalCount()).isNull();
        });
    }

    @Test
    void wrapper_shouldReturnTotalCount_whenExtractorReturnsValue() {
        final GitHubListSpec<String> spec = GitHubListSpec.wrapper(
                GitHubPageRequestKindValidators.PAGE, Wrapper.class, Wrapper::items, Wrapper::total);

        final GitHubPageBody<String> body = decodeWrapper(spec, "{\"items\":[\"a\"],\"total\":3}");

        assertSoftly(softly -> {
            softly.assertThat(body.items()).containsExactly("a");
            softly.assertThat(body.totalCount()).isEqualTo(3);
        });
    }

    @Test
    void wrapper_shouldReturnNullTotalCount_whenNoExtractor() {
        final GitHubListSpec<String> spec =
                GitHubListSpec.wrapper(GitHubPageRequestKindValidators.PAGE, Wrapper.class, Wrapper::items, null);

        assertThat(decodeWrapper(spec, "{\"items\":[\"a\"],\"total\":3}").totalCount())
                .isNull();
    }

    @Test
    void taskWrapper_shouldCarryTaskCountsInsteadOfTotalCount_whenBodyHasThem() {
        final GitHubListSpec<String> spec = GitHubListSpec.taskWrapper(
                GitHubPageRequestKindValidators.PAGE,
                Wrapper.class,
                Wrapper::items,
                wrapper -> new GitHubTaskCounts(wrapper.total(), 7));

        final GitHubPageBody<String> body = decodeWrapper(spec, "{\"items\":[\"a\"],\"total\":3}");

        assertSoftly(softly -> {
            softly.assertThat(body.items()).containsExactly("a");
            softly.assertThat(body.totalCount()).isNull();
            softly.assertThat(body.taskCounts()).isEqualTo(new GitHubTaskCounts(3, 7));
        });
    }

    @Test
    void taskWrapper_shouldReturnNoItems_whenExtractorReturnsNull() {
        final GitHubListSpec<String> spec = GitHubListSpec.taskWrapper(
                GitHubPageRequestKindValidators.PAGE,
                Wrapper.class,
                unused -> null,
                unused -> new GitHubTaskCounts(null, null));

        final GitHubPageBody<String> body = decodeWrapper(spec, "{\"items\":[\"a\"]}");

        assertThat(body.items()).isEmpty();
    }

    @Test
    void taskWrapper_shouldThrow_whenAnArgumentIsNull() {
        final Throwable noItems = catchThrowable(() -> GitHubListSpec.taskWrapper(
                GitHubPageRequestKindValidators.PAGE, Wrapper.class, null, unused -> new GitHubTaskCounts(1, 1)));
        final Throwable noCounts = catchThrowable(() ->
                GitHubListSpec.taskWrapper(GitHubPageRequestKindValidators.PAGE, Wrapper.class, Wrapper::items, null));

        assertThat(noItems).isInstanceOf(NullPointerException.class).hasMessageContaining("items");
        assertThat(noCounts).isInstanceOf(NullPointerException.class).hasMessageContaining("taskCounts");
    }

    @Test
    void wrapper_shouldSetMaxPerPage_whenOverloadGiven() {
        final GitHubListSpec<String> spec = GitHubListSpec.wrapper(
                GitHubPageRequestKindValidators.PAGE, Wrapper.class, Wrapper::items, Wrapper::total, 30);

        assertThat(spec.maxPerPage()).isEqualTo(30);
    }

    @Test
    void wrapper_shouldUseDefaultMaxPerPage_whenOverloadOmitted() {
        final GitHubListSpec<String> spec = GitHubListSpec.wrapper(
                GitHubPageRequestKindValidators.PAGE, Wrapper.class, Wrapper::items, Wrapper::total);

        assertThat(spec.maxPerPage()).isEqualTo(PaginationConfiguration.MAX_PER_PAGE);
    }

    @Test
    void maxPerPage_shouldSetMaxPerPage_whenBuilt() {
        final GitHubListSpec<String> spec = GitHubListSpec.<String>builder()
                .allowedKinds(GitHubPageRequestKindValidators.PAGE)
                .decoder(json -> response -> GitHubPageBody.of(List.of()))
                .maxPerPage(30)
                .build();

        assertThat(spec.maxPerPage()).isEqualTo(30);
        assertThat(spec.sendPerPage()).isTrue();
    }

    @Test
    void constructor_shouldThrow_whenMaxPerPageOutOfRange() {
        final Throwable thrown = catchThrowable(() -> GitHubListSpec.<String>builder()
                .allowedKinds(GitHubPageRequestKindValidators.PAGE)
                .decoder(json -> response -> GitHubPageBody.of(List.of()))
                .maxPerPage(0)
                .build());

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxPerPage");
    }

    @Test
    void pages_shouldThrow_whenTypeNull() {
        final Throwable thrown = catchThrowable(() -> GitHubListSpec.pages(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("type");
    }

    @Test
    void builder_shouldThrow_whenAllowedKindsNull() {
        final Throwable thrown =
                catchThrowable(() -> GitHubListSpec.<String>builder().allowedKinds(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void since_shouldThrow_whenTypeNull() {
        final Throwable thrown = catchThrowable(() -> GitHubListSpec.since(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("type");
    }

    @Test
    void startIndexWrapper_shouldAllowStartIndexKindWithoutPerPage_whenCalled() {
        final GitHubListSpec<String> spec =
                GitHubListSpec.startIndexWrapper(Wrapper.class, Wrapper::items, Wrapper::total);

        assertSoftly(softly -> {
            softly.assertThat(spec.allowedKinds()).isEqualTo(GitHubPageRequestKindValidators.START_INDEX);
            softly.assertThat(spec.sendPerPage()).isFalse();
            softly.assertThat(spec.maxPerPage()).isEqualTo(PaginationConfiguration.MAX_PER_PAGE);
        });
    }

    @Test
    void startIndexWrapper_shouldReturnTotalResults_whenBodyHasTotal() {
        final GitHubListSpec<String> spec =
                GitHubListSpec.startIndexWrapper(Wrapper.class, Wrapper::items, Wrapper::total);

        final GitHubPageBody<String> body = decodeWrapper(spec, "{\"items\":[\"a\"],\"total\":3}");

        assertSoftly(softly -> {
            softly.assertThat(body.items()).containsExactly("a");
            softly.assertThat(body.totalCount()).isEqualTo(3);
        });
    }

    @Test
    void startIndexWrapper_shouldThrow_whenTotalResultsNull() {
        final Throwable thrown =
                catchThrowable(() -> GitHubListSpec.startIndexWrapper(Wrapper.class, Wrapper::items, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("totalResults");
    }

    private static GitHubPageBody<String> decodeWrapper(final GitHubListSpec<String> spec, final String json) {
        final GitHubPageDecoder<String> decoder = spec.bindDecoder(new GitHubCodecListJson());

        return decoder.decode(HttpResponses.ok(json));
    }

    record Wrapper(List<String> items, Integer total) {}

    @Test
    void wrapper_shouldReturnAnEmptyList_whenTheItemsAreNull() {
        final GitHubListSpec<String> spec = GitHubListSpec.wrapper(
                GitHubPageRequestKindValidators.PAGE, Wrapper.class, Wrapper::items, Wrapper::total);

        final GitHubPageBody<String> body = decodeWrapper(spec, "{\"items\":null,\"total\":3}");

        assertSoftly(softly -> {
            softly.assertThat(body.items()).isEmpty();
            softly.assertThat(body.totalCount()).isEqualTo(3);
        });
    }
}
