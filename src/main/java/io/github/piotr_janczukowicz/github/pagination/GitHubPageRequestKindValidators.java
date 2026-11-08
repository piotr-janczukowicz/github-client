package io.github.piotr_janczukowicz.github.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Checks that a {@link GitHubPageRequest} uses a pagination kind the endpoint supports.
 *
 * <p>{@link GitHubNoneRequest} always passes: it adds no pagination query parameters. Call
 * {@link #requireAllowedPageRequestKind(GitHubPageRequest, Set)} with {@link #PAGE}, {@link #SINCE}, {@link #CURSOR},
 * {@link #PAGE_OR_CURSOR}, {@link #DELIVERY_CURSOR}, or {@link #START_INDEX} for the active style(s) the endpoint
 * accepts. Unsupported kinds throw {@link IllegalArgumentException}.
 */
public final class GitHubPageRequestKindValidators {

    /** Numbered {@code page} / {@code per_page} lists. */
    public static final Set<GitHubPageRequestKind> PAGE = unmodifiable(GitHubPageRequestKind.PAGE);

    /** Id-based {@code since} / {@code per_page} lists. */
    public static final Set<GitHubPageRequestKind> SINCE = unmodifiable(GitHubPageRequestKind.SINCE);

    /** Cursor {@code before} / {@code after} lists. */
    public static final Set<GitHubPageRequestKind> CURSOR = unmodifiable(GitHubPageRequestKind.CURSOR);

    /** Webhook delivery {@code cursor} / {@code per_page} lists. */
    public static final Set<GitHubPageRequestKind> DELIVERY_CURSOR =
            unmodifiable(GitHubPageRequestKind.DELIVERY_CURSOR);

    /** SCIM-style {@code startIndex} / {@code count} lists, whose next page is read from the body. */
    public static final Set<GitHubPageRequestKind> START_INDEX = unmodifiable(GitHubPageRequestKind.START_INDEX);

    /** Lists that accept numbered {@code page} / {@code per_page} or cursor {@code before} / {@code after}. */
    public static final Set<GitHubPageRequestKind> PAGE_OR_CURSOR =
            unmodifiable(GitHubPageRequestKind.PAGE, GitHubPageRequestKind.CURSOR);

    private GitHubPageRequestKindValidators() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures {@code request} is {@link GitHubNoneRequest} or one of the kinds in {@code allowed}.
     *
     * @param request pagination token
     * @param allowed accepted active kinds (prefer {@link #PAGE}, {@link #SINCE}, {@link #CURSOR}, or
     *     {@link #PAGE_OR_CURSOR})
     * @throws NullPointerException if {@code request} or {@code allowed} is {@code null}
     * @throws IllegalArgumentException if {@code request} is not {@link GitHubNoneRequest} and its kind is not in
     *     {@code allowed}
     */
    public static void requireAllowedPageRequestKind(
            final GitHubPageRequest request, final Set<GitHubPageRequestKind> allowed) {
        requireNonNull(request, "request");
        requireNonNull(allowed, "allowed");

        if (request instanceof GitHubNoneRequest) {
            return;
        }

        final GitHubPageRequestKind actual = GitHubPageRequestKind.of(request);
        if (allowed.contains(actual)) {
            return;
        }

        throw new IllegalArgumentException(
                "unsupported pagination request: " + actual.label() + "; allowed: none, " + labels(allowed));
    }

    /**
     * Ensures {@code request} has no explicit {@code per_page}.
     *
     * @param request pagination token
     * @param allowed accepted active kinds, used only in the error message (prefer {@link #SINCE} for since-only lists)
     * @throws NullPointerException if {@code request} or {@code allowed} is {@code null}
     * @throws IllegalArgumentException if {@code request} sets {@code per_page}
     */
    public static void requireNoExplicitPerPage(
            final GitHubPageRequest request, final Set<GitHubPageRequestKind> allowed) {
        requireNonNull(request, "request");
        requireNonNull(allowed, "allowed");

        if (request.explicitPerPage().isPresent()) {
            throw new IllegalArgumentException(
                    "unsupported pagination request: explicit per_page; allowed: none, " + labels(allowed));
        }
    }

    private static String labels(final Set<GitHubPageRequestKind> allowed) {
        final StringJoiner joiner = new StringJoiner(", ");
        for (final GitHubPageRequestKind kind : GitHubPageRequestKind.values()) {
            if (kind != GitHubPageRequestKind.NONE && allowed.contains(kind)) {
                joiner.add(kind.label());
            }
        }
        return joiner.toString();
    }

    private static Set<GitHubPageRequestKind> unmodifiable(final GitHubPageRequestKind kind) {
        return Collections.unmodifiableSet(EnumSet.of(kind));
    }

    private static Set<GitHubPageRequestKind> unmodifiable(
            final GitHubPageRequestKind first, final GitHubPageRequestKind second) {
        return Collections.unmodifiableSet(EnumSet.of(first, second));
    }
}
