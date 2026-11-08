package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code rel="next"} relation of an HTTP {@code Link} header; other relations are not needed for pagination.
 *
 * <p>Entries look like {@code <url>; rel="next"} or {@code <url>; rel=next}. The {@code rel} parameter may come after
 * other parameters, its name is case-insensitive, and its value may list several names separated by whitespace; only
 * the first {@code rel} parameter of an entry counts. Commas inside the {@code <url>} do not split entries, and a
 * {@code <}, {@code ;} or {@code rel=} inside a double-quoted parameter value is not treated as syntax. Backslash
 * escapes inside quoted values are not interpreted. A double quote that is never closed is ignored for splitting, so
 * one malformed entry cannot hide a later {@code next}. The first entry that names {@code next} wins.
 */
public final class GitHubLinkHeader {

    private static final String NEXT = "next";
    private static final String REL = "rel";
    private static final char TARGET_START = '<';
    private static final char TARGET_END = '>';
    private static final char PARAM_SEPARATOR = ';';
    private static final char QUOTE = '"';
    private static final Pattern PARAM = Pattern.compile("\\s*([^=\\s;]+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]*))");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");
    private static final String SCHEME_SEPARATOR = "://";
    private static final String GENERIC_TARGET = "<not a URI>";

    private final @Nullable URI nextUri;

    private GitHubLinkHeader(final @Nullable URI nextUri) {
        this.nextUri = nextUri;
    }

    /**
     * {@code rel="next"} URI, if present.
     *
     * @return next page, or empty
     */
    public Optional<URI> next() {
        return Optional.ofNullable(nextUri);
    }

    /**
     * Parses {@code headerValue}. Blank input is empty. Text that does not match a link entry, and entries for other
     * relations, are skipped.
     *
     * @param headerValue {@code Link} header, possibly blank
     * @return parsed header
     * @throws NullPointerException if {@code headerValue} is {@code null}
     * @throws GitHubPaginationException if an entry names {@code next} but its target is not a valid URI, so that
     *     pagination does not silently stop; the message holds only the target's scheme, host and path
     */
    public static GitHubLinkHeader parse(final String headerValue) {
        requireNonNull(headerValue, "headerValue");

        int position = 0;
        int open = headerValue.indexOf(TARGET_START, position);
        while (open >= 0) {
            final int close = headerValue.indexOf(TARGET_END, open + 1);
            if (close < 0) {
                break;
            }
            final int end = endOfParams(headerValue, close + 1);
            final URI target =
                    nextTarget(headerValue.substring(open + 1, close), headerValue.substring(close + 1, end));
            if (target != null) {
                return new GitHubLinkHeader(target);
            }
            position = end;
            open = headerValue.indexOf(TARGET_START, position);
        }
        return new GitHubLinkHeader(null);
    }

    // An entry's parameters run up to the next '<' that is not inside a quoted value. When the text ends inside a
    // quote, that quote was never closed, so the scan is repeated with quotes ignored.
    private static int endOfParams(final String text, final int from) {
        final int quotedEnd = endOfParams(text, from, true);
        return quotedEnd >= 0 ? quotedEnd : endOfParams(text, from, false);
    }

    // Returns -1 when the end of the text is reached inside a quote.
    private static int endOfParams(final String text, final int from, final boolean isQuoteAware) {
        boolean quoted = false;
        for (int i = from; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (isQuoteAware && c == QUOTE) {
                quoted = !quoted;
            } else if (c == TARGET_START && !quoted) {
                return i;
            }
        }
        return quoted ? -1 : text.length();
    }

    private static @Nullable URI nextTarget(final String target, final String params) {
        final String relValue = firstRel(params);
        if (relValue == null || !isNextRel(relValue)) {
            return null;
        }
        try {
            return URI.create(target.trim());
        } catch (final IllegalArgumentException unused) {
            // The parser message would quote the target, which may carry a token; safeDescription() reports it instead.
            throw new GitHubPaginationException(
                    "Link header names rel=next with a target that is not a valid URI: " + safeDescription(target));
        }
    }

    private static @Nullable String firstRel(final String params) {
        final List<String> segments = splitParams(params);
        // Segment 0 is the text between '>' and the first ';', never a parameter.
        for (int i = 1; i < segments.size(); i++) {
            final Matcher param = PARAM.matcher(segments.get(i));
            if (param.lookingAt() && REL.equalsIgnoreCase(param.group(1))) {
                final String quotedValue = param.group(2);
                return quotedValue != null ? quotedValue : param.group(3);
            }
        }
        return null;
    }

    private static List<String> splitParams(final String params) {
        // A quote that is never closed is ignored, like in endOfParams.
        final boolean isQuoteAware = !isEndingInsideQuote(params);
        final List<String> segments = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            final char c = params.charAt(i);
            if (isQuoteAware && c == QUOTE) {
                quoted = !quoted;
            } else if (c == PARAM_SEPARATOR && !quoted) {
                segments.add(params.substring(start, i));
                start = i + 1;
            }
        }
        segments.add(params.substring(start));
        return segments;
    }

    private static boolean isEndingInsideQuote(final String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == QUOTE) {
                quoted = !quoted;
            }
        }
        return quoted;
    }

    private static boolean isNextRel(final String relValue) {
        for (final String relation : WHITESPACE.split(relValue.trim())) {
            if (NEXT.equalsIgnoreCase(relation)) {
                return true;
            }
        }
        return false;
    }

    // Scheme, host and path only: the query, fragment and any user info are dropped because they may hold tokens.
    private static String safeDescription(final String target) {
        final String trimmed = target.trim();
        final int separator = trimmed.indexOf(SCHEME_SEPARATOR);
        if (separator < 1 || !SCHEME.matcher(trimmed.substring(0, separator)).matches()) {
            return GENERIC_TARGET;
        }
        final int start = separator + SCHEME_SEPARATOR.length();
        final String rest = trimmed.substring(start, endOfPath(trimmed, start));
        final int slash = rest.indexOf('/');
        final String authority = slash < 0 ? rest : rest.substring(0, slash);
        final String path = slash < 0 ? "" : rest.substring(slash);
        return trimmed.substring(0, start) + authority.substring(authority.lastIndexOf('@') + 1) + path;
    }

    private static int endOfPath(final String text, final int from) {
        for (int i = from; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '?' || c == '#') {
                return i;
            }
        }
        return text.length();
    }
}
