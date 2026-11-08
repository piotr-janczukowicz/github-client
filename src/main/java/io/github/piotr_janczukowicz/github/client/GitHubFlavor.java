package io.github.piotr_janczukowicz.github.client;

import java.time.Duration;

/** A GitHub REST description the API surface follows: GitHub.com, GitHub Enterprise Cloud or Enterprise Server. */
public enum GitHubFlavor {

    /** The GitHub.com description, {@code api.github.com}. */
    CLOUD(Duration.ofSeconds(1)),

    /** The GitHub Enterprise Cloud description, {@code ghec}. */
    GHEC(Duration.ofSeconds(1)),

    /**
     * The GitHub Enterprise Server description, {@code ghes}; a client of this flavor also names its
     * {@link GhesVersion}.
     */
    GHES(Duration.ZERO);

    private final Duration minMutationInterval;

    GitHubFlavor(final Duration minMutationInterval) {
        this.minMutationInterval = minMutationInterval;
    }

    /**
     * Least time between the starts of two mutations that a client of this flavor may be configured with. GitHub.com
     * limits content creation, so its flavors keep one second; an Enterprise Server instance sets its own limits, so
     * its clients may send mutations without a pause.
     *
     * @return the floor of {@link GitHubClientConfiguration.Builder#mutationInterval(Duration)}
     */
    public Duration minMutationInterval() {
        return minMutationInterval;
    }
}
