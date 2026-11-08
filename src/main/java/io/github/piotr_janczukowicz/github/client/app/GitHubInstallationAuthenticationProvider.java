package io.github.piotr_janczukowicz.github.client.app;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * {@link AuthenticationProvider} for a GitHub App installation: every {@link #authentication()} call returns a current
 * installation access token, fetched with a freshly signed app JWT and cached until shortly before it expires. Created
 * by {@link GitHubInstallationAuthenticationProviders}.
 *
 * <p>Installation tokens last one hour. The token is renewed once it is within {@code refreshMargin} of its expiry
 * (five minutes by default), so a request that starts with the returned token has time to finish. The margin never
 * exceeds half of the lifetime the token was issued with, so a short-lived token is not renewed on every call. A token
 * that is already expired by the local clock when it arrives, which happens when the local clock runs ahead of GitHub's
 * by more than the token's lifetime, cannot be used from the cache: it is returned to the caller once and the next call
 * fetches a new one. Keep the clock synchronised; the failure backoff below does not apply to this case.
 *
 * <p>Renewal is single-flight. While the current token has not expired, exactly one thread renews it and the others
 * keep getting the current token without waiting. Only when there is no unexpired token do callers wait for one fetch
 * instead of each making their own. A failed fetch is remembered for a backoff (one second, doubling up to thirty
 * seconds by default; see {@link GitHubInstallationAuthConfiguration.Builder#failureBackoff(Duration, Duration)}): in
 * that time {@link #authentication()} returns the unexpired token if there is one and otherwise throws a new
 * {@link GitHubInstallationTokenUnavailableException} without a new request. Its cause is the failure of the attempt
 * that started the backoff, and the thread that made that attempt gets the original exception.
 *
 * <p>A 401 does not renew the token every time. {@link GitHubClient} asks
 * {@link #isRenewableAfterRejection(Authentication)}, which drops the rejected token only when it is still the cached
 * one and was fetched at least {@code minRenewInterval} ago (ten seconds by default; see
 * {@link GitHubInstallationAuthConfiguration.Builder#minRenewInterval(Duration)}). A 401 that follows right after a
 * fetch means the credentials themselves are bad, and a new token would be rejected too, so the 401 is the result of
 * the call.
 *
 * <p>By default the token has every repository and permission of the installation. The repository and permission
 * configuration narrows it to what a task needs, so that a leaked token can do less. One instance has one scope.
 *
 * <p>Use it as the {@code auth} of a {@link GitHubClient}. The token requests are sent through a client of its own,
 * made by {@link GitHubInstallationAuthenticationProviders} with the user agent and configuration given there. Closing
 * the auth closes that client and its transport; the client that uses the auth stays usable.
 *
 * <p>{@link #authentication()} cannot throw checked exceptions: a transport failure surfaces as an
 * {@link UncheckedIOException}, and a rejected request (for example 401 for a bad key, 404 for an unknown installation)
 * as a {@link GitHubApiException}. Because {@link #authentication()} runs while a client prepares a call, a failed
 * token fetch surfaces from that call, so a 401 or 404 can come from the token request and not from the call itself;
 * the exception's URI tells which. {@code toString} shows no secrets.
 */
public sealed interface GitHubInstallationAuthenticationProvider extends AuthenticationProvider, AutoCloseable
        permits DefaultGitHubInstallationAuthenticationProvider {

    /**
     * Closes the client that requests tokens, and with it its transport. The client that uses this auth stays usable.
     */
    @Override
    void close();
}
