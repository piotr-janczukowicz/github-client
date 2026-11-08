package io.github.piotr_janczukowicz.github.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubPageStreamsTest {

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubPageStreams> constructor = GitHubPageStreams.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void itemSpliterator_shouldReportUnboundedSequentialCharacteristics() {
        final Spliterator<String> spliterator = GitHubPageStreams.items(
                        page(List.of("a"), unused -> Optional.empty(), 1))
                .spliterator();

        assertSoftly(softly -> {
            softly.assertThat(spliterator.trySplit()).isNull();
            softly.assertThat(spliterator.estimateSize()).isEqualTo(Long.MAX_VALUE);
            softly.assertThat(spliterator.characteristics()).isEqualTo(Spliterator.ORDERED | Spliterator.NONNULL);
        });
    }

    @Test
    void itemSpliterator_shouldSkipEmptyPage_whenNextPageHasItems() {
        final AtomicReference<@Nullable GitHubPage<String>> full = new AtomicReference<>();
        final GitHubPage<String> empty = page(List.of(), current -> Optional.ofNullable(full.get()), 1);
        full.set(page(List.of("a"), unused -> Optional.empty(), 2));

        final List<String> items = GitHubPageStreams.items(empty).toList();

        assertThat(items).containsExactly("a");
    }

    @Test
    void itemSpliterator_shouldWrapFailure_whenEmptyPageNextThrows() {
        final GitHubPage<String> empty = page(
                List.of(),
                unused -> {
                    throw new IOException("boom");
                },
                1);

        final Throwable thrown =
                catchThrowable(() -> GitHubPageStreams.items(empty).toList());

        assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasRootCauseMessage("boom");
    }

    @Test
    void pageSpliterator_shouldReturnFalse_whenAdvancedAfterLastPage() {
        final Spliterator<GitHubPage<String>> spliterator = GitHubPageStreams.pages(
                        page(List.of("a"), unused -> Optional.empty(), 1))
                .spliterator();

        assertSoftly(softly -> {
            softly.assertThat(spliterator.tryAdvance(unused -> {})).isTrue();
            softly.assertThat(spliterator.tryAdvance(unused -> {})).isFalse();
            softly.assertThat(spliterator.tryAdvance(unused -> {})).isFalse();
        });
    }

    @Test
    void pageSpliterator_shouldWrapFailure_whenNextThrows() {
        final AtomicReference<@Nullable GitHubPage<String>> seen = new AtomicReference<>();
        final GitHubPage<String> first = page(
                List.of("a"),
                unused -> {
                    throw new IOException("boom");
                },
                1);

        final Throwable thrown =
                catchThrowable(() -> GitHubPageStreams.pages(first).forEach(seen::set));

        assertSoftly(softly -> {
            softly.assertThat(seen.get()).isSameAs(first);
            softly.assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasRootCauseMessage("boom");
        });
    }

    @Test
    void pageSpliterator_shouldReportUnboundedSequentialCharacteristics() {
        final Spliterator<GitHubPage<String>> spliterator = GitHubPageStreams.pages(
                        page(List.of("a"), unused -> Optional.empty(), 1))
                .spliterator();

        assertSoftly(softly -> {
            softly.assertThat(spliterator.trySplit()).isNull();
            softly.assertThat(spliterator.estimateSize()).isEqualTo(Long.MAX_VALUE);
            softly.assertThat(spliterator.characteristics()).isEqualTo(Spliterator.ORDERED | Spliterator.NONNULL);
        });
    }

    @Test
    void all_shouldRethrowIoException_whenNextThrows() {
        final GitHubPage<String> empty = page(
                List.of(),
                unused -> {
                    throw new IOException("boom");
                },
                1);

        final Throwable thrown = catchThrowable(() -> GitHubPageStreams.all(empty));

        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("boom");
    }

    @Test
    void forEachPage_shouldRethrowIoException_whenNextThrows() {
        final GitHubPage<String> first = page(
                List.of("a"),
                unused -> {
                    throw new IOException("boom");
                },
                1);

        final Throwable thrown = catchThrowable(() -> GitHubPageStreams.forEachPage(first, unused -> {}));

        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("boom");
    }

    private static GitHubPage<String> page(final List<String> items, final Next next, final int pageCount) {
        return new GitHubPage<>(
                items,
                new GitHubPageWalk<>() {
                    @Override
                    public Optional<GitHubPage<String>> loadNext(final GitHubPage<String> current) throws IOException {
                        return next.load(current);
                    }

                    @Override
                    public void requireLatest(final GitHubPage<String> page) {
                        // The walk needs no latest-page check.
                    }
                },
                pageCount);
    }

    @FunctionalInterface
    private interface Next {

        Optional<GitHubPage<String>> load(GitHubPage<String> current) throws IOException;
    }

    @Test
    void checked_shouldReturnCauseWithoutSuppressedSelfReference_whenUnwrapped() {
        final IOException cause = new IOException("boom");
        final UncheckedIOException wrapper = new UncheckedIOException(cause);

        final IOException checked = GitHubPageStreams.checked(wrapper);

        assertSoftly(softly -> {
            softly.assertThat(checked).isSameAs(cause);
            softly.assertThat(checked.getSuppressed()).isEmpty();
        });
    }
}
