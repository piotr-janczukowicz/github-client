package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.internal.DefaultGitHubClient;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpOriginViolationException;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubCallerManagementTest {

    private static final GitHubManagementCredentials CREDENTIALS = new GitHubManagementCredentials("secret");

    private GitHubCaller caller;

    @BeforeEach
    void setUpCaller() {
        final HttpTransport transport = HttpTransportMocks.mock();
        caller = GitHubCaller.of(TestClients.client(TestHosts.GHES, transport));
    }

    @Test
    void managementCall_shouldTargetTheHostRootWithBasicAuth_whenPathBelowManage() {
        final HttpCall call = caller.managementCall(HttpMethod.GET, "/manage/v1/version", CREDENTIALS);

        assertSoftly(softly -> {
            softly.assertThat(call.method()).isEqualTo(HttpMethod.GET);
            softly.assertThat(call.target())
                    .isEqualTo(new HttpCall.AbsoluteUri(URI.create("https://ghe.example/manage/v1/version")));
            softly.assertThat(call.headers().get(HttpHeaderNames.AUTHORIZATION)).contains("Basic YXBpX2tleTpzZWNyZXQ=");
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/manage/v2/version",
                "/api/v3/version",
                "manage/v1/version",
                "/manage/v1",
                "/manage/v1/../../api/v3/user",
                "/manage/v1/%2e%2e/%2e%2e/api/v3/user",
                "/manage/v1/%2E%2E/x",
                "/manage/v1/./config",
                "/manage/v1/.%2e/x",
                "/manage/v1//x",
                "/manage/v1/x?y=1",
                "/manage/v1/x#f",
                "/manage/v1/%2f..",
                "/manage/v1/x%5cy",
                "/manage/v1/x\\y"
            })
    void managementCall_shouldThrow_whenPathIsNotAPlainPathBelowManage(final String path) {
        final Throwable thrown = catchThrowable(() -> caller.managementCall(HttpMethod.GET, path, CREDENTIALS));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/manage/v1/");
    }

    @Test
    void managementCall_shouldKeepDottedNames_whenSegmentIsNotADotSegment() {
        final HttpCall call = caller.managementCall(HttpMethod.GET, "/manage/v1/a.b/..c/d%2e", CREDENTIALS);

        assertThat(call.target())
                .isEqualTo(new HttpCall.AbsoluteUri(URI.create("https://ghe.example/manage/v1/a.b/..c/d%2e")));
    }

    @Test
    void managementCall_shouldThrow_whenCredentialsNull() {
        final Throwable thrown =
                catchThrowable(() -> caller.managementCall(HttpMethod.GET, "/manage/v1/version", null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("credentials");
    }

    @Test
    void management_shouldReturnTheConsoleView_whenBaseUriIsGhesApi() {
        final GitHubClient client = TestClients.client(TestHosts.GHES, HttpTransportMocks.mock());

        final GitHubClient management = client.management();

        assertThat(management.baseUri()).isEqualTo(URI.create("https://ghe.example/manage/v1/"));
    }

    @Test
    void management_shouldThrow_whenBaseUriIsNotGhesApi() {
        final GitHubClient client = TestClients.client(HttpTransportMocks.mock());

        final Throwable thrown = catchThrowable(client::management);

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("/api/v3/");
    }

    @Test
    void management_shouldThrow_whenCalledOnTheConsoleView() {
        final GitHubClient console =
                TestClients.client(TestHosts.GHES, HttpTransportMocks.mock()).management();

        final Throwable thrown = catchThrowable(console::management);

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("console view");
    }

    @Test
    void management_shouldThrow_whenFlavorIsCloud() {
        final GitHubClient client = DefaultGitHubClient.builder(
                        HttpTransportMocks.mock(), TestClients.configuration(TestHosts.GHES), TestClients.auth())
                .userAgent(TestClients.USER_AGENT)
                .flavor(GitHubFlavor.CLOUD)
                .build();

        final Throwable thrown = catchThrowable(client::management);

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("CLOUD");
    }

    @Test
    void execute_shouldRefuseTheConsole_whenSentThroughTheApiClient() {
        final GitHubClient client = TestClients.client(TestHosts.GHES, HttpTransportMocks.mock());
        final HttpCall call = HttpCall.of(HttpMethod.GET, URI.create("https://ghe.example/manage/v1/version"));

        final Throwable thrown = catchThrowable(() -> client.execute(call, Set.of(200), false));

        assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
    }

    @Test
    void execute_shouldRefuseTheApi_whenSentThroughTheConsoleView() {
        final GitHubClient management =
                TestClients.client(TestHosts.GHES, HttpTransportMocks.mock()).management();
        final HttpCall call = HttpCall.of(HttpMethod.GET, URI.create("https://ghe.example/api/v3/user"));

        final Throwable thrown = catchThrowable(() -> management.execute(call, Set.of(200), false));

        assertThat(thrown).isInstanceOf(HttpOriginViolationException.class);
    }

    @Test
    void management_shouldReturnTheCallerOfTheConsole_whenClientIsGhes() {
        final GitHubCaller console = caller.management();

        assertThat(console).isNotNull().isNotSameAs(caller);
    }

    @Test
    void managementCall_shouldRejectTheBasePathItself_whenNothingFollowsIt() {
        final Throwable thrown =
                catchThrowable(() -> caller.managementCall(HttpMethod.GET, "/manage/v1/", CREDENTIALS));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("below /manage/v1/");
    }
}
