package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.REQUEST_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpCallEvent;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubClientCallObserverTest {

    private static final URI BASE = URI.create("https://api.github.com/");

    private HttpTransport transport;

    private final List<HttpCallEvent> events = new ArrayList<>();

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void callObserver_shouldReceiveRequestIdAndStatus_whenCallSucceeds() throws Exception {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.of(REQUEST_ID, "AB12:34CD"), null));

        try (GitHubClient client = client()) {
            client.execute(HttpCall.get("/user"));
        }

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.statusCode()).isEqualTo(200);
            assertThat(event.target()).isEqualTo("https://api.github.com/user");
            assertThat(event.responseHeader(REQUEST_ID)).contains("AB12:34CD");
        });
    }

    @Test
    void callObserver_shouldThrow_whenNull() {
        final DefaultGitHubClient.Builder builder = builder();

        final Throwable thrown = catchThrowable(() -> builder.callObserver(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("observer");
    }

    private GitHubClient client() {
        return builder().callObserver(events::add).build();
    }

    private DefaultGitHubClient.Builder builder() {
        return DefaultGitHubClient.builder(transport, TestClients.configuration(BASE), TestClients.auth())
                .userAgent(TestClients.USER_AGENT);
    }
}
