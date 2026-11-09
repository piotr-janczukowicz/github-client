package io.github.piotr_janczukowicz.github.pagination;

import io.github.piotr_janczukowicz.util.ListUtils;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One GitHub Search API HTTP response: envelope metadata and decoded items.
 *
 * @param totalCount total matches reported by GitHub
 * @param incompleteResults whether GitHub considers the result set incomplete
 * @param items decoded items for this page
 * @param searchType issues-only {@code search_type} field, otherwise {@code null}
 * @param lexicalFallbackReasons issues-only fallback reasons, or {@code null}
 * @param <T> item type
 */
public record GitHubSearchEnvelope<T>(
        int totalCount,
        boolean incompleteResults,
        List<T> items,
        @Nullable GitHubIssueSearchType searchType,
        @Nullable List<GitHubLexicalFallbackReason> lexicalFallbackReasons) {

    public GitHubSearchEnvelope {
        items = ListUtils.copyOf(items, "items");
        lexicalFallbackReasons = ListUtils.copyOrNull(lexicalFallbackReasons);
    }
}
