package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireExactlyOne;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNegative;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requirePositive;

import java.security.PrivateKey;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link GitHubInstallationAuthenticationProvider} needs: the app, its private key, the installation, the scope
 * of the token and the renewal timing. The token requests go through the client the auth is made with, so the
 * configuration hold no transport, base URI or user agent. Built with {@link #builder()}; {@link Builder#build()}
 * rejects an incomplete or contradictory set.
 */
public final class GitHubInstallationAuthConfiguration {

    private final String appId;
    private final PrivateKey privateKey;
    private final long installationId;
    private final GitHubInstallationScope scope;
    private final Duration refreshMargin;
    private final Duration initialFailureBackoff;
    private final Duration maxFailureBackoff;
    private final Duration minRenewInterval;

    private GitHubInstallationAuthConfiguration(
            final String appId,
            final PrivateKey privateKey,
            final long installationId,
            final GitHubInstallationScope scope,
            final Duration refreshMargin,
            final Duration initialFailureBackoff,
            final Duration maxFailureBackoff,
            final Duration minRenewInterval) {
        this.appId = appId;
        this.privateKey = privateKey;
        this.installationId = installationId;
        this.scope = scope;
        this.refreshMargin = refreshMargin;
        this.initialFailureBackoff = initialFailureBackoff;
        this.maxFailureBackoff = maxFailureBackoff;
        this.minRenewInterval = minRenewInterval;
    }

    /**
     * Builder with the default renewal timing and the full scope of the installation.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * App identifier: its client id, or its numeric app id.
     *
     * @return app id
     */
    public String appId() {
        return appId;
    }

    /**
     * Private key that signs the app JWT.
     *
     * @return RSA private key
     */
    public PrivateKey privateKey() {
        return privateKey;
    }

    /**
     * Installation the tokens are for.
     *
     * @return installation id, positive
     */
    public long installationId() {
        return installationId;
    }

    /**
     * How long before its expiry a token is renewed.
     *
     * @return refresh margin, not negative
     */
    public Duration refreshMargin() {
        return refreshMargin;
    }

    /**
     * Pause after the first failed token fetch.
     *
     * @return initial failure backoff, positive
     */
    public Duration initialFailureBackoff() {
        return initialFailureBackoff;
    }

    /**
     * Longest pause after repeated failed token fetches.
     *
     * @return maximum failure backoff, not shorter than the initial one
     */
    public Duration maxFailureBackoff() {
        return maxFailureBackoff;
    }

    /**
     * How long a token must have been cached before a 401 renews it.
     *
     * @return minimum age, not negative
     */
    public Duration minRenewInterval() {
        return minRenewInterval;
    }

    /**
     * Repositories and permissions the tokens are limited to.
     *
     * @return scope of the token
     */
    GitHubInstallationScope scope() {
        return scope;
    }

    /** Builds a {@link GitHubInstallationAuthConfiguration}. */
    public static final class Builder {
        private @Nullable String appId;
        private @Nullable PrivateKey privateKey;
        private @Nullable String privateKeyPem;
        private long installationId;
        private GitHubInstallationScope scope = GitHubInstallationScope.ALL;
        private Duration refreshMargin = Duration.ofMinutes(5);
        private Duration initialFailureBackoff = Duration.ofSeconds(1);
        private Duration maxFailureBackoff = Duration.ofSeconds(30);
        private Duration minRenewInterval = Duration.ofSeconds(10);

        private Builder() {}

        /**
         * Identifies the app: its client id (recommended) or its numeric app id.
         *
         * @param appId app identifier; letters, digits, {@code .}, {@code _} and {@code -}
         * @return this builder
         * @throws NullPointerException if {@code appId} is {@code null}
         * @throws IllegalArgumentException if {@code appId} is blank or has other characters
         */
        public Builder appId(final String appId) {
            requireNotBlank(appId, "appId");
            this.appId = GitHubAppJwts.requireAppId(appId);
            return this;
        }

        /**
         * Private key of the app, as PEM text; see {@link GitHubAppPrivateKeys}. Exactly one of this and
         * {@link #privateKey(PrivateKey)} must be set. The text is parsed by {@link #build()}.
         *
         * @param pem PEM text, PKCS#1 or PKCS#8
         * @return this builder
         * @throws NullPointerException if {@code pem} is {@code null}
         */
        public Builder privateKeyPem(final String pem) {
            this.privateKeyPem = requireNonNull(pem, "pem");
            return this;
        }

        /**
         * Private key of the app. Exactly one of this and {@link #privateKeyPem(String)} must be set.
         *
         * @param privateKey RSA private key
         * @return this builder
         * @throws NullPointerException if {@code privateKey} is {@code null}
         */
        public Builder privateKey(final PrivateKey privateKey) {
            this.privateKey = requireNonNull(privateKey, "privateKey");
            return this;
        }

        /**
         * Installation to act as.
         *
         * @param installationId installation id, positive
         * @return this builder
         * @throws IllegalArgumentException if {@code installationId} is not positive
         */
        public Builder installationId(final long installationId) {
            this.installationId = requirePositive(installationId, "installationId");
            return this;
        }

        /**
         * Limits the token to the repositories with these names (without the owner), which must be among the ones the
         * installation can access. By default the token covers all of them. A token has one scope, so an app that needs
         * several uses one {@link GitHubInstallationAuthenticationProvider} for each.
         *
         * @param repositoryNames repository names, not empty, at most 500, none blank and none containing {@code /}
         * @return this builder
         * @throws NullPointerException if the collection or an element is {@code null}
         * @throws IllegalArgumentException if the collection is empty or has more than 500 names, or a name is blank or
         *     contains {@code /}
         */
        public Builder repositories(final Collection<String> repositoryNames) {
            this.scope = scope.withRepositories(repositoryNames);
            return this;
        }

        /**
         * Limits the token to the repositories with these ids; see {@link #repositories(Collection)}, which it can be
         * combined with.
         *
         * @param ids repository ids, not empty, at most 500 and all positive
         * @return this builder
         * @throws NullPointerException if the collection or an element is {@code null}
         * @throws IllegalArgumentException if the collection is empty or has more than 500 ids, or an id is not
         *     positive
         */
        public Builder repositoryIds(final Collection<Long> ids) {
            this.scope = scope.withRepositoryIds(ids);
            return this;
        }

        /**
         * Limits the token to these permissions, each at most the level the installation was granted. By default the
         * token has all of the installation's permissions. GitHub names them in the request body of {@code POST
         * /app/installations/{id}/access_tokens}, for example {@code contents} to {@code read} or {@code issues} to
         * {@code write}; the names and levels are passed on as given and GitHub rejects unknown ones.
         *
         * @param permissionLevels permission name to level, not empty, no blank name or level
         * @return this builder
         * @throws NullPointerException if the map or an entry is {@code null}
         * @throws IllegalArgumentException if the map is empty or a name or level is blank
         */
        public Builder permissions(final Map<String, String> permissionLevels) {
            this.scope = scope.withPermissions(permissionLevels);
            return this;
        }

        /**
         * How long before its expiry a token is renewed. Defaults to 5 minutes. The margin is capped at half of the
         * lifetime a token is issued with, so a margin as long as the lifetime renews the token halfway through it.
         *
         * @param refreshMargin renewal margin, not negative
         * @return this builder
         * @throws NullPointerException if {@code refreshMargin} is {@code null}
         * @throws IllegalArgumentException if {@code refreshMargin} is negative
         */
        public Builder refreshMargin(final Duration refreshMargin) {
            this.refreshMargin = requireNonNegative(refreshMargin, "refreshMargin");
            return this;
        }

        /**
         * How long a failed token fetch is remembered. After a failure
         * {@link GitHubInstallationAuthenticationProvider#authentication()} makes no new request for {@code initial};
         * each further failure doubles the pause up to {@code max}, and a success resets it. Defaults to 1 second and
         * 30 seconds.
         *
         * @param initial pause after the first failure, positive
         * @param max longest pause, not shorter than {@code initial}
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if {@code initial} is not positive or {@code max} is shorter
         */
        public Builder failureBackoff(final Duration initial, final Duration max) {
            requireNonNull(initial, "initial");
            requireNonNull(max, "max");
            if (initial.isZero() || initial.isNegative()) {
                throw new IllegalArgumentException("initial backoff must be positive");
            }
            if (max.compareTo(initial) < 0) {
                throw new IllegalArgumentException("max backoff must not be shorter than the initial backoff");
            }
            this.initialFailureBackoff = initial;
            this.maxFailureBackoff = max;
            return this;
        }

        /**
         * How long a token must have been cached before a 401 renews it. A 401 for a younger token is returned to the
         * caller, see
         * {@link GitHubInstallationAuthenticationProvider#isRenewableAfterRejection(io.github.piotr_janczukowicz.http.client.authentication.Authentication)}.
         * Defaults to 10 seconds; zero renews on every 401.
         *
         * @param minRenewInterval minimum age of a rejected token, not negative
         * @return this builder
         * @throws NullPointerException if {@code minRenewInterval} is {@code null}
         * @throws IllegalArgumentException if {@code minRenewInterval} is negative
         */
        public Builder minRenewInterval(final Duration minRenewInterval) {
            this.minRenewInterval = requireNonNegative(minRenewInterval, "minRenewInterval");
            return this;
        }

        /**
         * Validates and builds the configuration. A PEM text is parsed here, so an invalid one is rejected here.
         *
         * @return configuration
         * @throws NullPointerException if the app id was not set
         * @throws IllegalArgumentException if the installation id was not set, neither or both of the private key forms
         *     were set, or the PEM text is not an unencrypted RSA private key
         */
        public GitHubInstallationAuthConfiguration build() {
            final String id = requireNonNull(appId, "appId");
            requirePositive(installationId, "installationId");
            requireExactlyOne(privateKey, privateKeyPem, "privateKey or privateKeyPem");
            final PrivateKey key = privateKey != null
                    ? privateKey
                    : GitHubAppPrivateKeys.parse(requireNonNull(privateKeyPem, "privateKeyPem"));
            return new GitHubInstallationAuthConfiguration(
                    id,
                    key,
                    installationId,
                    scope,
                    refreshMargin,
                    initialFailureBackoff,
                    maxFailureBackoff,
                    minRenewInterval);
        }
    }
}
