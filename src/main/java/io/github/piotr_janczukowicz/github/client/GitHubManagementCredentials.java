package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;

import io.github.piotr_janczukowicz.http.client.authentication.Authentication;

/**
 * Credentials of the GitHub Enterprise Server management console: the root site administrator password, sent as HTTP
 * Basic authentication with the user {@code api_key}. They are not the API token of the client and are used for one
 * call only.
 *
 * @param password management console password
 */
public record GitHubManagementCredentials(String password) {

    private static final String USER = "api_key";

    public GitHubManagementCredentials {
        requireNotBlank(password, "password");
    }

    /**
     * Describes these credentials without the password.
     *
     * @return text with {@code password} redacted
     */
    @Override
    public String toString() {
        return "GitHubManagementCredentials[password=<redacted>]";
    }

    /**
     * Basic authentication of the management console for these credentials.
     *
     * @return authentication, never printed
     */
    Authentication authentication() {
        return Authentication.ofBasic(USER, password);
    }
}
