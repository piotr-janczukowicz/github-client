package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A GitHub Enterprise Server release, such as {@code 3.22}.
 *
 * <p>Any release can be written as a value; {@link GitHubClientConfiguration.Builder#build()} accepts only the ones in
 * {@link #supported()}, because the library can say what an operation's availability is only for the releases whose
 * REST description it has seen.
 *
 * @param major major number, at least 1
 * @param minor minor number, at least 0
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
public record GhesVersion(int major, int minor) implements Comparable<GhesVersion> {

    private static final Pattern TEXT = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})");

    // Matches the GHES releases GitHub documents. Only releases for which GitHub publishes
    // the REST description of the API version the library follows (3.21 and later for 2026-03-10) are supported.
    private static final List<GhesVersion> SUPPORTED = List.of(new GhesVersion(3, 21), new GhesVersion(3, 22));

    public GhesVersion {
        requireAtLeast(major, 1, "major");
        requireAtLeast(minor, 0, "minor");
    }

    /**
     * Reads a version written as {@code MAJOR.MINOR}, for example {@code 3.22}.
     *
     * @param text the version text
     * @return the version
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws IllegalArgumentException if {@code text} is not {@code MAJOR.MINOR} with decimal numbers
     */
    public static GhesVersion parse(final String text) {
        final Matcher matcher = TEXT.matcher(requireNonNull(text, "text"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("a GitHub Enterprise Server version must look like 3.22, got: " + text);
        }
        return new GhesVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /**
     * The releases the library supports, oldest first.
     *
     * @return unmodifiable list, never empty
     */
    public static List<GhesVersion> supported() {
        return SUPPORTED;
    }

    @Override
    public int compareTo(final GhesVersion other) {
        final int byMajor = Integer.compare(major, other.major);
        return byMajor == 0 ? Integer.compare(minor, other.minor) : byMajor;
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
