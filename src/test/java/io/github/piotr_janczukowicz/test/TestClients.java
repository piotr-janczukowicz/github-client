package io.github.piotr_janczukowicz.test;

import io.github.piotr_janczukowicz.github.client.GitHubCaller;
import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import java.net.URI;

public final class TestClients {

    public static final URI GITHUB_BASE = TestHosts.GITHUB;

    public static final String TOKEN = "gh-token";

    public static final String USER_AGENT = "test-agent/1.0";

    private TestClients() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String perPageQuery() {
        return "per_page=" + 100;
    }

    public static AuthenticationProvider auth() {
        return AuthenticationProviders.bearer(TOKEN);
    }

    public static HttpClientConfiguration.Builder configurationBuilder(final URI baseUri) {
        return HttpClientConfiguration.builder(baseUri);
    }

    public static HttpClientConfiguration configuration(final URI baseUri) {
        return configurationBuilder(baseUri).build();
    }

    public static GitHubClient client(final HttpTransport transport) {
        return client(GITHUB_BASE, transport);
    }

    public static GitHubClient client(final URI baseUri, final HttpTransport transport) {
        return TestGitHubClients.raw(transport, configuration(baseUri));
    }

    public static GitHubCaller caller(final GitHubClient client) {
        return GitHubCaller.of(client);
    }

    public static GitHubCaller caller(final HttpTransport transport) {
        return caller(GITHUB_BASE, transport);
    }

    public static GitHubCaller caller(final URI baseUri, final HttpTransport transport) {
        return GitHubCaller.of(client(baseUri, transport));
    }
}
