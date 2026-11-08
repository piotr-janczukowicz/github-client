package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.GitHubCaller;
import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.GitHubClientConfiguration;
import io.github.piotr_janczukowicz.github.client.GitHubClients;
import java.time.Clock;

/**
 * Factory for {@link GitHubInstallationAuthenticationProvider}. Each auth makes its token requests through a client of
 * its own, made with {@link GitHubClients#createTokenClient} from the given {@code userAgent} and
 * {@code clientConfiguration}, which authenticates as the app. That client has its own transport and rate-limit state,
 * like any other client, and the auth closes it in {@link GitHubInstallationAuthenticationProvider#close()}. A
 * rate-limit listener or call observer of {@code clientConfiguration} also receives the quota and the calls of the
 * token requests; their quota is the app's, not the installation's, so give the listener a configuration of its own
 * when mixing the two is unwanted. Use the same {@code clientConfiguration} as for the client that uses the
 * installation auth, so that both reach the same base URI, through the same proxy and with the same trust.
 */
public final class GitHubInstallationAuthenticationProviders {

    private GitHubInstallationAuthenticationProviders() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Auth for one installation, with the default renewal timing and the full scope of the installation, timed by the
     * system clock.
     *
     * @param userAgent {@code User-Agent} of the token requests, see {@link GitHubClients#create}
     * @param clientConfiguration configuration of the client that makes the token requests
     * @param appId app identifier: its client id (recommended) or its numeric app id
     * @param privateKeyPem private key of the app, as PEM text, PKCS#1 or PKCS#8
     * @param installationId installation to act as, positive
     * @return installation auth
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the user agent, app id, PEM text or installation id is invalid
     */
    public static GitHubInstallationAuthenticationProvider create(
            final String userAgent,
            final GitHubClientConfiguration clientConfiguration,
            final String appId,
            final String privateKeyPem,
            final long installationId) {
        return create(userAgent, clientConfiguration, appId, privateKeyPem, installationId, Clock.systemUTC());
    }

    /**
     * Auth for one installation, with the default renewal timing and the full scope of the installation, timed by
     * {@code clock}.
     *
     * @param userAgent {@code User-Agent} of the token requests, see {@link GitHubClients#create}
     * @param clientConfiguration configuration of the client that makes the token requests
     * @param appId app identifier: its client id (recommended) or its numeric app id
     * @param privateKeyPem private key of the app, as PEM text, PKCS#1 or PKCS#8
     * @param installationId installation to act as, positive
     * @param clock source of the time of the app JWT and of the token expiry and refresh
     * @return installation auth
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the user agent, app id, PEM text or installation id is invalid
     */
    public static GitHubInstallationAuthenticationProvider create(
            final String userAgent,
            final GitHubClientConfiguration clientConfiguration,
            final String appId,
            final String privateKeyPem,
            final long installationId,
            final Clock clock) {
        return create(
                userAgent,
                clientConfiguration,
                GitHubInstallationAuthConfiguration.builder()
                        .appId(appId)
                        .privateKeyPem(privateKeyPem)
                        .installationId(installationId)
                        .build(),
                clock);
    }

    /**
     * Auth for one installation with the given configuration, timed by the system clock.
     *
     * @param userAgent {@code User-Agent} of the token requests, see {@link GitHubClients#create}
     * @param clientConfiguration configuration of the client that makes the token requests
     * @param configuration app, key, installation, scope and renewal timing
     * @return installation auth; no request is made until the first
     *     {@link GitHubInstallationAuthenticationProvider#authentication()}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the user agent is invalid
     */
    public static GitHubInstallationAuthenticationProvider create(
            final String userAgent,
            final GitHubClientConfiguration clientConfiguration,
            final GitHubInstallationAuthConfiguration configuration) {
        return create(userAgent, clientConfiguration, configuration, Clock.systemUTC());
    }

    /**
     * Auth for one installation with the given configuration, timed by {@code clock}: the app JWT is signed for its
     * time, and the token expiry and refresh decisions are made on it.
     *
     * @param userAgent {@code User-Agent} of the token requests, see {@link GitHubClients#create}
     * @param clientConfiguration configuration of the client that makes the token requests
     * @param configuration app, key, installation, scope and renewal timing
     * @param clock source of the time of the app JWT and of the token expiry and refresh
     * @return installation auth; no request is made until the first
     *     {@link GitHubInstallationAuthenticationProvider#authentication()}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the user agent is invalid
     */
    public static GitHubInstallationAuthenticationProvider create(
            final String userAgent,
            final GitHubClientConfiguration clientConfiguration,
            final GitHubInstallationAuthConfiguration configuration,
            final Clock clock) {
        requireNonNull(userAgent, "userAgent");
        requireNonNull(clientConfiguration, "clientConfiguration");
        final GitHubInstallationAuthConfiguration checkedConfiguration = requireNonNull(configuration, "configuration");
        final Clock checkedClock = requireNonNull(clock, "clock");
        final GitHubClient tokenClient = GitHubClients.createTokenClient(
                GitHubAppAuthenticationProviders.app(
                        checkedConfiguration.appId(), checkedConfiguration.privateKey(), checkedClock),
                userAgent,
                clientConfiguration);
        return create(tokenClient, checkedConfiguration, checkedClock);
    }

    /**
     * Auth that makes its token requests through {@code tokenClient}, which must authenticate as the app and which the
     * auth closes. The public factories make that client; tests pass one over a scripted transport.
     */
    static GitHubInstallationAuthenticationProvider create(
            final GitHubClient tokenClient,
            final GitHubInstallationAuthConfiguration configuration,
            final Clock clock) {
        return new DefaultGitHubInstallationAuthenticationProvider(
                configuration.appId(),
                configuration.installationId(),
                tokenClient,
                new GitHubInstallationTokenCache(
                        configuration.installationId(),
                        configuration.initialFailureBackoff(),
                        configuration.maxFailureBackoff(),
                        configuration.minRenewInterval(),
                        clock,
                        new GitHubInstallationTokenFetcher(
                                configuration.refreshMargin(),
                                clock,
                                GitHubCaller.of(tokenClient),
                                configuration.scope().toRequestBody())));
    }
}
