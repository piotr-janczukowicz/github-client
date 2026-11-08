package io.github.piotr_janczukowicz.github.client.app;

import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;

/**
 * {@link AuthenticationProvider} as the GitHub App itself, for the app-level endpoints such as {@code GET /app} and
 * {@code GET /app/installations}. Create it with {@link GitHubAppAuthenticationProviders}.
 *
 * <p>Every request is sent with an app JWT signed for the time of that request, and the rate-limit identity is
 * {@code app:{appId}}. A 401 is not renewed with a new JWT. The {@code toString} shows only the app id, never the key
 * or a JWT.
 */
public sealed interface GitHubAppAuthenticationProvider extends AuthenticationProvider
        permits DefaultGitHubAppAuthenticationProvider {}
