package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.pagination.GitHubTaskCounts;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Decoded items of one list HTTP page, plus an optional envelope {@code total_count} and the agent task counts.
 *
 * @param items items for this page
 * @param totalCount total matches from the wrapper body, or {@code null} when the response is a bare array
 * @param taskCounts the task counts of an agent task list body, or {@code null} for any other list
 * @param <T> item type
 */
public record GitHubPageBody<T>(
        List<T> items,
        @Nullable Integer totalCount,
        @Nullable GitHubTaskCounts taskCounts) {

    public GitHubPageBody {
        items = List.copyOf(requireNonNull(items, "items"));
    }

    /**
     * Page body with no total count.
     *
     * @param items items for this page
     * @param <T> item type
     * @return body
     * @throws NullPointerException if {@code items} is {@code null}
     */
    public static <T> GitHubPageBody<T> of(final List<T> items) {
        return new GitHubPageBody<>(items, null, null);
    }

    /**
     * Page body with a wrapper {@code total_count}.
     *
     * @param items items for this page
     * @param totalCount total matches
     * @param <T> item type
     * @return body
     * @throws NullPointerException if {@code items} is {@code null}
     */
    public static <T> GitHubPageBody<T> of(final List<T> items, final int totalCount) {
        return new GitHubPageBody<>(items, totalCount, null);
    }

    /**
     * Page body of an agent task list, which has no single {@code total_count} but two task counts.
     *
     * @param items items for this page
     * @param taskCounts the counts of the body
     * @param <T> item type
     * @return body
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T> GitHubPageBody<T> of(final List<T> items, final GitHubTaskCounts taskCounts) {
        return new GitHubPageBody<>(items, null, requireNonNull(taskCounts, "taskCounts"));
    }
}
