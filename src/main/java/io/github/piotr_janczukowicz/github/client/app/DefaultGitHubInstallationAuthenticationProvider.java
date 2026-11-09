package io.github.piotr_janczukowicz.github.client.app;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.json.JsonDecodeException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Default {@link GitHubInstallationAuthenticationProvider}, created by
 * {@link GitHubInstallationAuthenticationProviders}. It owns the {@link GitHubInstallationTokenCache} of one
 * installation; see {@link GitHubInstallationAuthenticationProvider} for the behaviour of the token.
 *
 * <p>Its rate-limit identity is {@code app:{appId}/installation:{installationId}}, which does not change when the token
 * rotates. The {@code toString} shows the app id and installation id, never a key or a token.
 */
final class DefaultGitHubInstallationAuthenticationProvider implements GitHubInstallationAuthenticationProvider {

    private final String appId;
    private final long installationId;
    private final GitHubClient tokenClient;
    private final GitHubInstallationTokenCache cache;

    DefaultGitHubInstallationAuthenticationProvider(
            final String appId,
            final long installationId,
            final GitHubClient tokenClient,
            final GitHubInstallationTokenCache cache) {
        this.appId = appId;
        this.installationId = installationId;
        this.tokenClient = tokenClient;
        this.cache = cache;
    }

    /**
     * Bearer authentication of the current installation access token. Its identity is
     * {@code app:{appId}/installation:{installationId}}, which does not change when the token rotates: a rate limit
     * that the installation hit before a renewal still holds after it, and another installation of the same app is not
     * held back by it.
     *
     * @return the current token as a bearer authentication
     * @throws UncheckedIOException if the transport fails while fetching a new token, or the thread is interrupted
     *     while it waits for another thread's fetch
     * @throws GitHubApiException if GitHub rejects the token request
     * @throws GitHubInstallationTokenUnavailableException if an earlier fetch failed and the failure backoff has not
     *     ended
     */
    @Override
    public Optional<Authentication> authentication() {
        return Optional.of(Authentication.ofBearer(token()).withIdentity(installationIdentity()));
    }

    /**
     * Drops the cached token when GitHub rejected it, so {@link GitHubClient} sends the call again with a new token. A
     * burst of rejections for the same token mints one new token.
     *
     * <p>The cached token is dropped only if it was fetched at least {@code minRenewInterval} ago. A token that GitHub
     * rejects right after it was issued will be rejected again, so it stays and {@code false} is returned: with bad
     * credentials a persistent 401 costs one token request per interval, not one per call. A {@code token} that is no
     * longer the cached one has already been replaced or dropped, so the retry uses a different token.
     *
     * @param rejected the authentication GitHub rejected; its value is the token
     * @return {@code true} if the next {@link #authentication()} no longer uses that token; {@code false} if the token
     *     is too young to be renewed
     */
    @Override
    public boolean isRenewableAfterRejection(final Authentication rejected) {
        return cache.isRenewableAfterRejection(rejected.value());
    }

    /**
     * Closes the client that requests tokens, and with it its transport. The client that uses this auth stays usable.
     */
    @Override
    public void close() {
        tokenClient.close();
    }

    @Override
    public String toString() {
        return "GitHubInstallationAuthenticationProvider[appId=" + appId + ", installationId=" + installationId + "]";
    }

    /**
     * Current installation access token.
     *
     * @return token, valid for at least the refresh margin
     * @throws UncheckedIOException if the transport fails while fetching a new token, or the thread is interrupted
     *     while it waits for another thread's fetch (the interrupt flag stays set, the cause is an
     *     {@link java.io.InterruptedIOException})
     * @throws GitHubApiException if GitHub rejects the token request
     * @throws GitHubInstallationTokenUnavailableException if an earlier fetch failed and the failure backoff has not
     *     ended
     * @throws JsonDecodeException if the token response is not valid JSON
     * @throws IllegalStateException if the token response has no body, token or expiry
     */
    private String token() {
        return cache.token();
    }

    private String installationIdentity() {
        return "app:" + appId + "/installation:" + installationId;
    }
}
