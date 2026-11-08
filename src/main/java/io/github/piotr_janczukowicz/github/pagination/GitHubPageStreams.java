package io.github.piotr_janczukowicz.github.pagination;

import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/** Sequential lazy streams over a {@link GitHubPage} walk. */
final class GitHubPageStreams {

    private GitHubPageStreams() {
        throw new UnsupportedOperationException("Utility class");
    }

    static <T> Stream<T> items(final GitHubPage<T> first) {
        return StreamSupport.stream(new ItemSpliterator<>(first), false);
    }

    static <T> Stream<GitHubPage<T>> pages(final GitHubPage<T> first) {
        return StreamSupport.stream(new PageSpliterator<>(first), false);
    }

    static <T> List<T> all(final GitHubPage<T> first) throws IOException {
        try {
            return items(first).toList();
        } catch (final WalkFailure ex) {
            throw checked(ex);
        }
    }

    static <T> void forEachPage(final GitHubPage<T> first, final Consumer<GitHubPage<T>> consumer) throws IOException {
        try {
            pages(first).forEach(consumer);
        } catch (final WalkFailure ex) {
            throw checked(ex);
        }
    }

    static IOException checked(final UncheckedIOException failure) {
        return failure.getCause();
    }

    /** I/O failure of the page walk itself; one thrown by a consumer is not this type and is left alone. */
    private static final class WalkFailure extends UncheckedIOException {

        @Serial
        private static final long serialVersionUID = 1L;

        WalkFailure(final IOException cause) {
            super(cause);
        }
    }

    private static final class ItemSpliterator<T> implements Spliterator<T> {

        private @Nullable GitHubPage<T> page;
        private int index;

        private ItemSpliterator(final GitHubPage<T> first) {
            this.page = first;
        }

        @Override
        public boolean tryAdvance(final Consumer<? super T> action) {
            while (page != null) {
                final List<T> current = page.items();
                if (index < current.size()) {
                    action.accept(current.get(index));
                    index++;
                    return true;
                }
                try {
                    page = page.next().orElse(null);
                } catch (final IOException ex) {
                    throw new WalkFailure(ex);
                }
                index = 0;
            }
            return false;
        }

        @Override
        public @Nullable Spliterator<T> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return Long.MAX_VALUE;
        }

        @Override
        public int characteristics() {
            return ORDERED | NONNULL;
        }
    }

    private static final class PageSpliterator<T> implements Spliterator<GitHubPage<T>> {

        private GitHubPage<T> page;
        private boolean isReady;
        private boolean isFinished;

        private PageSpliterator(final GitHubPage<T> first) {
            this.page = first;
            this.isReady = true;
        }

        @Override
        public boolean tryAdvance(final Consumer<? super GitHubPage<T>> action) {
            if (isFinished) {
                return false;
            }
            if (isReady) {
                action.accept(page);
                isReady = false;
                return true;
            }
            try {
                final Optional<GitHubPage<T>> following = page.next();
                if (following.isEmpty()) {
                    isFinished = true;
                    return false;
                }
                page = following.get();
                action.accept(page);
                return true;
            } catch (final IOException ex) {
                throw new WalkFailure(ex);
            }
        }

        @Override
        public @Nullable Spliterator<GitHubPage<T>> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return Long.MAX_VALUE;
        }

        @Override
        public int characteristics() {
            return ORDERED | NONNULL;
        }
    }
}
