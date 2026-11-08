/**
 * Authentication as a GitHub App: signing the app JWT and keeping a current installation token. A
 * {@link io.github.piotr_janczukowicz.github.client.app.GitHubInstallationAuthenticationProvider} is made by
 * {@link io.github.piotr_janczukowicz.github.client.app.GitHubInstallationAuthenticationProviders} from a
 * {@link io.github.piotr_janczukowicz.github.client.GitHubClientConfiguration} and a user agent, and its token requests
 * go through a client of its own. Closing an auth closes that client and its transport.
 */
@NullMarked
package io.github.piotr_janczukowicz.github.client.app;

import org.jspecify.annotations.NullMarked;
