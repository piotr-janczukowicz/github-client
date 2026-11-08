package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.security.PrivateKey;
import java.time.Clock;

/**
 * Factory for {@link GitHubAppAuthenticationProvider}, the auth of the GitHub App itself. The overloads without a clock
 * sign with the system clock; use the overloads with a clock to inject another clock.
 */
public final class GitHubAppAuthenticationProviders {

    private GitHubAppAuthenticationProviders() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * App auth for the PEM private key, signing with the system clock.
     *
     * @param appId app identifier: its client id (recommended) or its numeric app id
     * @param privateKeyPem private key of the app, as PEM text, PKCS#1 or PKCS#8, unencrypted
     * @return app auth
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the app id has other characters, or the PEM text is not an unencrypted RSA
     *     private key
     */
    public static GitHubAppAuthenticationProvider app(final String appId, final String privateKeyPem) {
        return app(appId, privateKeyPem, Clock.systemUTC());
    }

    /**
     * App auth for the PEM private key, signing with {@code clock}.
     *
     * @param appId app identifier: its client id (recommended) or its numeric app id
     * @param privateKeyPem private key of the app, as PEM text, PKCS#1 or PKCS#8, unencrypted
     * @param clock source of the time of each JWT
     * @return app auth
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the app id has other characters, or the PEM text is not an unencrypted RSA
     *     private key
     */
    public static GitHubAppAuthenticationProvider app(
            final String appId, final String privateKeyPem, final Clock clock) {
        return app(appId, GitHubAppPrivateKeys.parse(requireNonNull(privateKeyPem, "privateKeyPem")), clock);
    }

    /**
     * App auth for the private key, signing with the system clock.
     *
     * @param appId app identifier: its client id (recommended) or its numeric app id
     * @param privateKey RSA private key of the app
     * @return app auth
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the app id has other characters
     */
    public static GitHubAppAuthenticationProvider app(final String appId, final PrivateKey privateKey) {
        return app(appId, privateKey, Clock.systemUTC());
    }

    /**
     * App auth for the private key, signing with {@code clock}.
     *
     * @param appId app identifier: its client id (recommended) or its numeric app id
     * @param privateKey RSA private key of the app
     * @param clock source of the time of each JWT
     * @return app auth
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the app id has other characters
     */
    public static GitHubAppAuthenticationProvider app(
            final String appId, final PrivateKey privateKey, final Clock clock) {
        return new DefaultGitHubAppAuthenticationProvider(appId, privateKey, clock);
    }
}
