package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import java.time.Clock;

final class TestInstallationAuths {

    private TestInstallationAuths() {
        throw new UnsupportedOperationException("Utility class");
    }

    static GitHubInstallationAuthenticationProvider create(
            final HttpTransport transport, final GitHubInstallationAuthConfiguration configuration) {
        return create(transport, configuration, Clock.systemUTC());
    }

    static GitHubInstallationAuthenticationProvider create(
            final HttpTransport transport, final GitHubInstallationAuthConfiguration configuration, final Clock clock) {
        return create(transport, TestClients.configuration(TestClients.GITHUB_BASE), configuration, clock);
    }

    static GitHubInstallationAuthenticationProvider create(
            final HttpTransport transport,
            final HttpClientConfiguration clientConfiguration,
            final GitHubInstallationAuthConfiguration configuration,
            final Clock clock) {
        requireNonNull(transport, "transport");
        requireNonNull(clientConfiguration, "clientConfiguration");
        requireNonNull(configuration, "configuration");
        requireNonNull(clock, "clock");
        final GitHubClient tokenClient = TestGitHubClients.raw(
                transport,
                clientConfiguration,
                GitHubAppAuthenticationProviders.app(configuration.appId(), configuration.privateKey(), clock));
        return GitHubInstallationAuthenticationProviders.create(tokenClient, configuration, clock);
    }

    static GitHubInstallationAuthenticationProvider create(
            final HttpTransport transport, final String appId, final String privateKeyPem, final long installationId) {
        return create(transport, appId, privateKeyPem, installationId, Clock.systemUTC());
    }

    static GitHubInstallationAuthenticationProvider create(
            final HttpTransport transport,
            final String appId,
            final String privateKeyPem,
            final long installationId,
            final Clock clock) {
        requireNonNull(transport, "transport");
        return create(
                transport,
                GitHubInstallationAuthConfiguration.builder()
                        .appId(appId)
                        .privateKeyPem(privateKeyPem)
                        .installationId(installationId)
                        .build(),
                clock);
    }
}
