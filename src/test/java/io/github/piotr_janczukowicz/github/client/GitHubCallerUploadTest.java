package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@UnitTest
class GitHubCallerUploadTest {

    private static final String ASSETS = "/repos/octo/hello/releases/1/assets";

    @ParameterizedTest
    @CsvSource({
        "https://api.github.com/,https://uploads.github.com/repos/octo/hello/releases/1/assets",
        "https://api.octocorp.ghe.com/,https://uploads.octocorp.ghe.com/repos/octo/hello/releases/1/assets",
        "https://ghe.example/api/v3/,https://ghe.example/api/uploads/repos/octo/hello/releases/1/assets"
    })
    void uploadCall_shouldAimAtTheUploadsOrigin_whenBaseKnown(final String base, final String expected) {
        final GitHubCaller caller = caller(URI.create(base));
        final HttpBody body = HttpBody.of(new byte[] {1});
        final HttpCall call = HttpCall.of(HttpMethod.POST, ASSETS)
                .withQuery(Map.of("name", "a.zip"))
                .withHeader("X-Test", "1")
                .withBody(body);

        final HttpCall uploads = caller.uploadCall(call);

        assertSoftly(softly -> {
            softly.assertThat(uploads.target()).isEqualTo(new HttpCall.AbsoluteUri(URI.create(expected)));
            softly.assertThat(uploads.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(uploads.query()).containsEntry("name", List.of("a.zip"));
            softly.assertThat(uploads.headers().get("X-Test")).contains("1");
            softly.assertThat(uploads.body()).isEqualTo(body);
        });
    }

    @Test
    void uploadCall_shouldThrow_whenBaseHasNoUploadsOrigin() {
        final GitHubCaller caller = caller(URI.create("http://localhost:8080/"));
        final HttpCall call = HttpCall.of(HttpMethod.POST, ASSETS);

        assertThat(catchThrowable(() -> caller.uploadCall(call))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void uploadCall_shouldThrow_whenCallTargetsAbsoluteUri() {
        final GitHubCaller caller = caller(TestHosts.GITHUB);
        final HttpCall call = HttpCall.of(HttpMethod.POST, URI.create("https://uploads.github.com/x"));

        assertThat(catchThrowable(() -> caller.uploadCall(call))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadCall_shouldThrow_whenCallNull() {
        final GitHubCaller caller = caller(TestHosts.GITHUB);

        assertThat(catchThrowable(() -> caller.uploadCall(null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("call");
    }

    private static GitHubCaller caller(final URI base) {
        return GitHubCaller.of(TestClients.client(base, HttpTransportMocks.mock()));
    }
}
