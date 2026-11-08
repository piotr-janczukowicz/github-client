package io.github.piotr_janczukowicz.github.client;

import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.TestRetryingTransports;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class TestGitHubClients {

    public static final Duration TEST_MUTATION_INTERVAL = Duration.ofMillis(1);

    private TestGitHubClients() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static GitHubClient raw(final HttpTransport transport) {
        return raw(transport, TestClients.configuration(TestClients.GITHUB_BASE));
    }

    public static GitHubClient flavored(final HttpTransport transport, final GitHubFlavor flavor) {
        return DefaultGitHubClient.builder(
                        transport, TestClients.configuration(TestClients.GITHUB_BASE), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .flavor(flavor)
                .build();
    }

    public static GitHubClient ghes(final HttpTransport transport, final GhesVersion version) {
        return DefaultGitHubClient.builder(
                        transport, TestClients.configuration(TestClients.GITHUB_BASE), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .flavor(GitHubFlavor.GHES)
                .ghesVersion(version)
                .build();
    }

    public static GitHubClient ghes(
            final HttpTransport transport, final HttpClientConfiguration configuration, final GhesVersion version) {
        return DefaultGitHubClient.builder(transport, configuration, TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .flavor(GitHubFlavor.GHES)
                .ghesVersion(version)
                .build();
    }

    public static GitHubClient raw(final HttpTransport transport, final HttpClientConfiguration configuration) {
        return raw(transport, configuration, TestClients.auth());
    }

    public static GitHubClient raw(
            final HttpTransport transport,
            final HttpClientConfiguration configuration,
            final AuthenticationProvider auth) {
        return DefaultGitHubClient.builder(transport, configuration, auth)
                .userAgent(TestClients.USER_AGENT)
                .build();
    }

    public static GitHubClient raw(
            final HttpTransport transport,
            final HttpClientConfiguration configuration,
            final PaginationConfiguration pagination) {
        return DefaultGitHubClient.builder(transport, configuration, TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .pagination(pagination)
                .build();
    }

    public static GitHubClient defaultStack(final HttpTransport transport) {
        return defaultStack(transport, GitHubClientConfiguration.defaults());
    }

    public static GitHubClient defaultStack(
            final HttpTransport transport, final GitHubClientConfiguration configuration) {
        return defaultStack(transport, configuration, TEST_MUTATION_INTERVAL);
    }

    public static GitHubClient defaultStack(
            final HttpTransport transport,
            final GitHubClientConfiguration configuration,
            final Duration mutationInterval) {
        return defaultStack(transport, TestClients.auth(), configuration, mutationInterval);
    }

    public static GitHubClient defaultStack(final HttpTransport transport, final AuthenticationProvider auth) {
        return defaultStack(transport, auth, GitHubClientConfiguration.defaults(), TEST_MUTATION_INTERVAL);
    }

    public static GitHubClient defaultStack(
            final HttpTransport transport,
            final AuthenticationProvider auth,
            final GitHubClientConfiguration configuration,
            final Duration mutationInterval) {
        final MutableClock clock = new MutableClock(Instant.EPOCH);
        final List<Duration> sleeps = new ArrayList<>();
        return GitHubClients.assemble(
                        transport,
                        auth,
                        TestClients.USER_AGENT,
                        configuration,
                        mutationInterval,
                        (delegate, retry) -> TestRetryingTransports.create(
                                delegate, retry, TestRetryingTransports.recordingAndAdvancing(sleeps, clock), clock))
                .build();
    }

    public static HttpTransport retrying(final HttpTransport delegate, final RetryConfiguration retry) {
        return GitHubClients.retrying(delegate, retry);
    }
}
