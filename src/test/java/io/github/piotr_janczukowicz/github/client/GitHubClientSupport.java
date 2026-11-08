package io.github.piotr_janczukowicz.github.client;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.test.TestClients;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

final class GitHubClientSupport {

    private GitHubClientSupport() {}

    static HttpResponse get(final GitHubClient client, final String path) throws IOException {
        return client.execute(HttpCall.get(path));
    }

    static HttpResponse get(final GitHubClient client, final String path, final Map<String, String> query)
            throws IOException {
        return client.execute(HttpCall.get(path).withQuery(query));
    }

    static HttpResponse get(
            final GitHubClient client,
            final String path,
            final Map<String, String> query,
            final HttpHeaders overlayHeaders)
            throws IOException {
        return client.execute(HttpCall.get(path).withQuery(query).withHeaders(overlayHeaders));
    }

    static HttpResponse getUri(
            final GitHubClient client, final URI uri, final Map<String, String> query, final HttpHeaders overlayHeaders)
            throws IOException {
        return client.execute(HttpCall.get(uri).withQuery(query).withHeaders(overlayHeaders));
    }

    static HttpResponse getUri(final GitHubClient client, final URI uri, final HttpHeaders overlayHeaders)
            throws IOException {
        return getUri(client, uri, Map.of(), overlayHeaders);
    }

    static HttpResponse getWithoutFollowingRedirects(
            final GitHubClient client,
            final String path,
            final Map<String, String> query,
            final HttpHeaders overlayHeaders)
            throws IOException {
        return client.execute(HttpCall.get(path).withQuery(query).withHeaders(overlayHeaders), Set.of(), false);
    }

    static URI redirectLocation(final GitHubClient client, final String path, final HttpHeaders overlayHeaders)
            throws IOException {
        return TestClients.caller(client).redirectLocation(HttpCall.get(path).withHeaders(overlayHeaders));
    }

    static URI redirectLocation(final GitHubClient client, final URI uri, final HttpHeaders overlayHeaders)
            throws IOException {
        return TestClients.caller(client).redirectLocation(HttpCall.get(uri).withHeaders(overlayHeaders));
    }

    static HttpResponse send(final GitHubClient client, final HttpMethod method, final String path) throws IOException {
        return client.execute(HttpCall.of(method, path));
    }

    static HttpResponse send(final GitHubClient client, final HttpMethod method, final String path, final HttpBody body)
            throws IOException {
        return client.execute(HttpCall.of(method, path).withBody(body));
    }

    static HttpResponse post(final GitHubClient client, final String path, final HttpBody body) throws IOException {
        return client.execute(HttpCall.of(HttpMethod.POST, path).withBody(body));
    }

    static HttpResponse post(
            final GitHubClient client, final String path, final Map<String, String> query, final HttpBody body)
            throws IOException {
        return client.execute(
                HttpCall.of(HttpMethod.POST, path).withQuery(query).withBody(body));
    }

    static HttpResponse post(
            final GitHubClient client,
            final String path,
            final Map<String, String> query,
            final HttpBody body,
            final HttpHeaders overlayHeaders)
            throws IOException {
        return client.execute(HttpCall.of(HttpMethod.POST, path)
                .withQuery(query)
                .withBody(body)
                .withHeaders(overlayHeaders));
    }

    static HttpResponse postUri(
            final GitHubClient client, final URI uri, final HttpBody body, final HttpHeaders overlayHeaders)
            throws IOException {
        return client.execute(HttpCall.of(HttpMethod.POST, uri).withBody(body).withHeaders(overlayHeaders));
    }

    static HttpResponse put(final GitHubClient client, final String path, final HttpBody body) throws IOException {
        return client.execute(HttpCall.of(HttpMethod.PUT, path).withBody(body));
    }

    static HttpResponse put(
            final GitHubClient client, final String path, final Map<String, String> query, final HttpBody body)
            throws IOException {
        return client.execute(HttpCall.of(HttpMethod.PUT, path).withQuery(query).withBody(body));
    }

    static HttpResponse patch(final GitHubClient client, final String path, final HttpBody body) throws IOException {
        return client.execute(HttpCall.of(HttpMethod.PATCH, path).withBody(body));
    }

    static HttpResponse patch(
            final GitHubClient client, final String path, final Map<String, String> query, final HttpBody body)
            throws IOException {
        return client.execute(
                HttpCall.of(HttpMethod.PATCH, path).withQuery(query).withBody(body));
    }

    static HttpResponse delete(final GitHubClient client, final String path) throws IOException {
        return client.execute(HttpCall.of(HttpMethod.DELETE, path));
    }

    static HttpResponse delete(final GitHubClient client, final String path, final Map<String, String> query)
            throws IOException {
        return client.execute(HttpCall.of(HttpMethod.DELETE, path).withQuery(query));
    }

    static HttpResponse delete(
            final GitHubClient client, final String path, final Map<String, String> query, final HttpBody body)
            throws IOException {
        return client.execute(
                HttpCall.of(HttpMethod.DELETE, path).withQuery(query).withBody(body));
    }
}
