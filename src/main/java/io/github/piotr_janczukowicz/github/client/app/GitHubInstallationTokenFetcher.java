package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.StringUtils.isBlank;

import io.github.piotr_janczukowicz.github.client.GitHubCaller;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GitHubInstallationTokenFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubInstallationTokenFetcher.class);

    private final Duration refreshMargin;
    private final Clock clock;
    private final GitHubCaller caller;
    private final Map<String, Object> scope;

    GitHubInstallationTokenFetcher(
            final Duration refreshMargin,
            final Clock clock,
            final GitHubCaller caller,
            final Map<String, Object> scope) {
        this.refreshMargin = refreshMargin;
        this.clock = clock;
        this.caller = caller;
        this.scope = scope;
    }

    GitHubInstallationTokenCache.CachedToken fetch(final long installationId) {
        LOG.atDebug()
                .setMessage("Fetching an access token for installation {} ({})")
                .addArgument(installationId)
                .addArgument(() -> scope.isEmpty() ? "unscoped" : "scoped to " + scope.keySet())
                .log();
        final HttpCall post = HttpCall.post("/app/installations/" + installationId + "/access_tokens");
        final HttpCall call = scope.isEmpty() ? post : caller.json(post, scope);
        final HttpResult<InstallationToken> result;
        try {
            result = caller.fetchResult(call, InstallationToken.class);
        } catch (final IOException transportFailure) {
            throw new UncheckedIOException("could not fetch an installation token", transportFailure);
        }
        final InstallationToken body =
                result.value().orElseThrow(() -> new IllegalStateException("GitHub returned no installation token"));
        final String token = body.token();
        final Instant serverExpiresAt = body.expiresAt();
        if (isBlank(token) || serverExpiresAt == null) {
            throw new IllegalStateException("GitHub returned an installation token response without token or expiry");
        }
        return cachedToken(
                token, serverExpiresAt, result.response().serverDate().orElse(null));
    }

    // expires_at is GitHub's time. When the response carries GitHub's Date, the remaining lifetime is measured on
    // GitHub's clock and laid on the local one, so that a local clock that is off neither expires the token at once nor
    // keeps an expired one in use.
    private GitHubInstallationTokenCache.CachedToken cachedToken(
            final String token, final Instant serverExpiresAt, final @Nullable Instant serverDate) {
        final Instant fetchedAt = clock.instant();
        final Instant expiresAt =
                serverDate == null ? serverExpiresAt : fetchedAt.plus(Duration.between(serverDate, serverExpiresAt));
        final Duration half = Duration.between(fetchedAt, expiresAt).dividedBy(2);
        final Duration effectiveMargin = refreshMargin.compareTo(half) < 0 ? refreshMargin : half;
        final Instant refreshAt = effectiveMargin.isNegative() ? expiresAt : expiresAt.minus(effectiveMargin);
        LOG.atDebug()
                .setMessage("Installation token fetched at {}: GitHub says it expires at {}, which is {} locally"
                        + " (local clock minus GitHub's Date header: {}); refresh at {}")
                .addArgument(fetchedAt)
                .addArgument(serverExpiresAt)
                .addArgument(expiresAt)
                .addArgument(() -> serverDate == null
                        ? "no Date header, clocks assumed equal"
                        : Duration.between(serverDate, fetchedAt))
                .addArgument(refreshAt)
                .log();
        return new GitHubInstallationTokenCache.CachedToken(token, fetchedAt, expiresAt, refreshAt);
    }

    /** Token response of GitHub; package-private so that its redacting {@code toString} can be tested. */
    record InstallationToken(
            @Nullable String token, @Nullable Instant expiresAt) {

        @Override
        public String toString() {
            return "InstallationToken[token=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }
}
