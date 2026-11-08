package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireMatches;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs the short-lived JSON Web Token that identifies a GitHub App (RS256).
 *
 * <p>GitHub rejects a token that is valid for more than ten minutes, or issued in the future, so the token is issued
 * one minute in the past to absorb clock drift and expires nine minutes after the current time.
 */
final class GitHubAppJwts {

    static final Duration BACKDATE = Duration.ofSeconds(60);
    static final Duration LIFETIME = Duration.ofMinutes(9);
    /** What an app id may consist of; anything else could break out of the JSON string of the {@code iss} claim. */
    static final Pattern APP_ID = Pattern.compile("\\A[A-Za-z0-9._-]+\\z");

    private static final Logger LOG = LoggerFactory.getLogger(GitHubAppJwts.class);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";

    private GitHubAppJwts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Signed token for {@code appId}.
     *
     * @param appId app client id or numeric app id; only letters, digits, {@code .}, {@code _} and {@code -}
     * @param key RSA private key of the app
     * @param now current time
     * @return compact serialized JWT
     * @throws IllegalArgumentException if {@code appId} has other characters, or {@code key} cannot sign with RS256
     */
    static String create(final String appId, final PrivateKey key, final Instant now) {
        requireAppId(appId);
        LOG.atDebug()
                .setMessage("Signing an app JWT for app {}: issued at {} (backdated {} for clock drift), expires at {}")
                .addArgument(appId)
                .addArgument(() -> now.minus(BACKDATE))
                .addArgument(BACKDATE)
                .addArgument(() -> now.plus(LIFETIME))
                .log();
        final String payload = "{\"iat\":" + now.minus(BACKDATE).getEpochSecond() + ",\"exp\":"
                + now.plus(LIFETIME).getEpochSecond() + ",\"iss\":\"" + appId + "\"}";
        final String encodedHeader = URL_ENCODER.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
        final String encodedPayload = URL_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return sign(encodedHeader + "." + encodedPayload, key);
    }

    /**
     * Requires {@code appId} to consist only of characters that are safe inside the {@code iss} claim.
     *
     * @param appId app client id or numeric app id
     * @return {@code appId}
     * @throws NullPointerException if {@code appId} is {@code null}
     * @throws IllegalArgumentException if {@code appId} has characters other than letters, digits, {@code .}, {@code _}
     *     and {@code -}
     */
    static String requireAppId(final String appId) {
        return requireMatches(appId, APP_ID, "appId");
    }

    /**
     * Appends the RS256 signature of {@code signingInput} to it.
     *
     * @param signingInput {@code base64url(header) + "." + base64url(payload)}
     * @param key RSA private key
     * @return compact serialized JWT
     * @throws IllegalArgumentException if {@code key} cannot sign with RS256
     */
    static String sign(final String signingInput, final PrivateKey key) {
        return signingInput + "." + URL_ENCODER.encodeToString(signature(signingInput, key));
    }

    private static byte[] signature(final String unsigned, final PrivateKey key) {
        try {
            final Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (final GeneralSecurityException failure) {
            throw new IllegalArgumentException("the key cannot sign with RS256", failure);
        }
    }
}
