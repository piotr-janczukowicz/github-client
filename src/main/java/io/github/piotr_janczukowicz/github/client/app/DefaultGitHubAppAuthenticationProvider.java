package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import java.security.PrivateKey;
import java.time.Clock;
import java.util.Optional;

/**
 * Default {@link GitHubAppAuthenticationProvider}, created by {@link GitHubAppAuthenticationProviders}. Every request
 * is sent with an app JWT signed for the time of that request, so the JWT is valid for nine minutes and does not need
 * to be cached (see {@link GitHubAppJwts}).
 *
 * <p>Its rate-limit identity is {@code app:{appId}} whatever JWT is sent, because every JWT names the same app. A block
 * from GitHub's rate limit therefore holds across the JWTs that follow it.
 *
 * <p>A 401 is never renewed: signing a new JWT for the same app cannot fix a rejected one, so
 * {@link #isRenewableAfterRejection(Authentication)} is always {@code false} and the 401 is the result of the call. The
 * {@code toString} shows only the app id, never the key or a JWT.
 */
final class DefaultGitHubAppAuthenticationProvider implements GitHubAppAuthenticationProvider {

    private final String appId;
    private final PrivateKey key;
    private final Clock clock;

    /**
     * Auth for the app, signing with {@code key} at the time {@code clock} shows for each request.
     *
     * @param appId app identifier: its client id (recommended) or its numeric app id; letters, digits, {@code .},
     *     {@code _} and {@code -}
     * @param key RSA private key of the app
     * @param clock source of the time of each JWT; a fixed clock makes the claims predictable
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code appId} has other characters
     */
    DefaultGitHubAppAuthenticationProvider(final String appId, final PrivateKey key, final Clock clock) {
        this.appId = GitHubAppJwts.requireAppId(appId);
        this.key = requireNonNull(key, "key");
        this.clock = requireNonNull(clock, "clock");
    }

    /**
     * A JWT signed now, sent as {@code Bearer <jwt>} for the app, whichever JWT it is: the identity of the app is
     * {@code app:{appId}}, so a limit that GitHub put on the app holds for the next JWT too.
     *
     * @return the signed JWT as a bearer authentication
     * @throws IllegalArgumentException if the key cannot sign with RS256
     */
    @Override
    public Optional<Authentication> authentication() {
        return Optional.of(Authentication.ofBearer(GitHubAppJwts.create(appId, key, clock.instant()))
                .withIdentity("app:" + appId));
    }

    @Override
    public String toString() {
        return "GitHubAppAuthenticationProvider[appId=" + appId + "]";
    }
}
