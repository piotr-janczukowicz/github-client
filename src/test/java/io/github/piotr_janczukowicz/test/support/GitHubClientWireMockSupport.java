package io.github.piotr_janczukowicz.test.support;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;

import io.github.piotr_janczukowicz.github.client.GitHubCaller;
import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.github.json.GitHubJsonCodecs;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.util.FileUtils;
import java.net.URI;

/** WireMock-style helpers for the integration tests of the transport. */
public final class GitHubClientWireMockSupport {

    public static final JsonCodec CODEC = GitHubJsonCodecs.defaults();

    private GitHubClientWireMockSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static WireMockClient client(final StubHttpServer http) {
        return client(http, PaginationConfiguration.defaults());
    }

    public static WireMockClient client(final StubHttpServer http, final PaginationConfiguration pagination) {
        final HttpTransport transport = HttpTransports.okHttp();
        final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(http.baseUri()), pagination);
        return new WireMockClient(transport, client);
    }

    public static byte[] jsonBytes(final Class<?> anchor, final String file) {
        return FileUtils.readBytes(anchor, file);
    }

    public static StubHttpServer.StubResponse okJson(final byte[] body) {
        return status(OK).header(CONTENT_TYPE, "application/json").body(body);
    }

    public static String linkNext(final URI nextUri) {
        return "<" + nextUri + ">; rel=\"next\"";
    }

    public record WireMockClient(HttpTransport transport, GitHubClient client) implements AutoCloseable {

        public GitHubCaller caller() {
            return GitHubCaller.of(client);
        }

        @Override
        public void close() {
            client.close();
            transport.close();
        }
    }
}
