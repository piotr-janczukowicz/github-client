package io.github.piotr_janczukowicz.github.pagination;

import org.jspecify.annotations.Nullable;

/**
 * The counts that the agent task list endpoints report next to the page of tasks.
 *
 * @param totalActiveCount {@code total_active_count}, the total count of active (non-archived) tasks, or {@code null}
 *     when the body omits it
 * @param totalArchivedCount {@code total_archived_count}, the total count of archived tasks, or {@code null} when the
 *     body omits it
 */
public record GitHubTaskCounts(
        @Nullable Integer totalActiveCount, @Nullable Integer totalArchivedCount) {}
