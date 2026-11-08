package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpMethod.POST;
import static io.github.piotr_janczukowicz.http.HttpStatus.CREATED;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.github.client.GitHubClientConfiguration;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

@IntegrationTest
@Isolated
class GitHubInstallationAuthenticationProvidersIntegrationTest {

    private static final String TOKEN_PATH = "/app/installations/42/access_tokens";

    @RegisterExtension
    static final StubHttpServer HTTP = new StubHttpServer("127.0.0.1");

    @Test
    void token_shouldFetchInstallationToken_whenAuthMadeOverItsOwnTransport() throws Exception {
        stubToken();

        try (final GitHubInstallationAuthenticationProvider auth = TestInstallationAuths.create(
                HttpTransports.okHttp(),
                HttpClientConfiguration.of(HTTP.baseUri()),
                configuration(),
                Clock.systemUTC())) {
            assertThat(auth.authentication().orElseThrow().value()).isEqualTo("ghs_default");
        }
    }

    @Test
    void create_shouldFetchInstallationToken_whenUserAgentAndConfigurationGiven() throws Exception {
        stubToken();
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(HTTP.baseUri()))
                .build();

        try (final GitHubInstallationAuthenticationProvider auth = GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, configuration())) {
            assertThat(auth.authentication().orElseThrow().value()).isEqualTo("ghs_default");
        }
    }

    @Test
    void close_shouldFailTokenFetch_whenAuthClosedBeforeFirstFetch() throws Exception {
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(HTTP.baseUri()))
                .build();
        final GitHubInstallationAuthenticationProvider auth = GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, configuration());

        auth.close();
        final Throwable thrown = catchThrowable(auth::authentication);

        assertThat(thrown).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void create_shouldFetchInstallationToken_whenAppIdAndPemAndClockGiven() {
        stubToken();
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(HTTP.baseUri()))
                .build();
        final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

        try (final GitHubInstallationAuthenticationProvider auth = GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, "Iv1.test", AppKeys.pkcs1Pem(), 42, clock)) {
            assertThat(auth.authentication().orElseThrow().value()).isEqualTo("ghs_default");
        }
    }

    @Test
    void create_shouldFetchInstallationToken_whenAppIdAndPemGiven() {
        stubToken();
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(HTTP.baseUri()))
                .build();
        final String token;

        try (final GitHubInstallationAuthenticationProvider auth = GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, "Iv1.test", AppKeys.pkcs1Pem(), 42)) {
            token = auth.authentication().orElseThrow().value();
        }

        assertThat(token).isEqualTo("ghs_default");
    }

    private static void stubToken() {
        HTTP.stub(
                POST,
                TOKEN_PATH,
                status(CREATED)
                        .header(CONTENT_TYPE, "application/json")
                        .body("{\"token\":\"ghs_default\",\"expires_at\":\"2099-01-01T00:00:00Z\"}"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    private static GitHubInstallationAuthConfiguration configuration() throws Exception {
        return GitHubInstallationAuthConfiguration.builder()
                .appId("Iv1.test")
                .privateKey(privateKey())
                .installationId(42)
                .build();
    }

    private static PrivateKey privateKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPrivate();
    }
}
