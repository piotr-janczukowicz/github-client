package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.mockito.Mockito.verify;

import io.github.piotr_janczukowicz.github.pagination.GitHubSearchEnvelope;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import io.github.piotr_janczukowicz.util.FileUtils;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

final class GitHubCallerFixture {

    static final PathTemplate REPOS_OCTOCAT_TARBALL = PathTemplate.of("/repos/octocat/Hello-World/tarball/main");
    static final PathTemplate SEARCH_TOPICS = PathTemplate.of("/search/topics");
    static final PathTemplate USERS = PathTemplate.of("/users");
    static final PathTemplate MARKDOWN_RAW = PathTemplate.of("/markdown/raw");
    static final PathTemplate REPOS_ONE = PathTemplate.of("/repos/1");
    static final PathTemplate MARKDOWN = PathTemplate.of("/markdown");
    static final PathTemplate REPOS = PathTemplate.of("/repos");
    static final PathTemplate USER = PathTemplate.of("/user");
    static final URI UPLOAD_URI = URI.create("https://uploads.github.com/repos/octocat/Hello-World/releases/1/assets");

    static final Function<JsonNode, GitHubSearchEnvelope<String>> TOPIC_ENVELOPE = tree -> new GitHubSearchEnvelope<>(
            tree.get("total_count").asInt(),
            tree.get("incomplete_results").asBoolean(),
            List.of(tree.get("items").get(0).get("name").asString()),
            null,
            null);

    private GitHubCallerFixture() {
        throw new UnsupportedOperationException("Utility class");
    }

    static Wiring mocked() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final GitHubCaller caller = TestClients.caller(transport);
        return new Wiring(transport, caller, new TestApi(caller));
    }

    static HttpResponse okResponse() {
        return response(HttpStatus.OK.code(), null);
    }

    static String json(final String fileName) {
        return FileUtils.readString(GitHubCallerFixture.class, fileName);
    }

    static HttpRequest singleRequest(final HttpTransport transport) throws IOException {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        return captor.getValue();
    }

    record Wiring(HttpTransport transport, GitHubCaller caller, TestApi api) {}
}
