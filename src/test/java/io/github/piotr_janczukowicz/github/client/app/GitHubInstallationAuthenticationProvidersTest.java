package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.USER_AGENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.GitHubClientConfiguration;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;

@UnitTest
@Isolated
class GitHubInstallationAuthenticationProvidersTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void create_shouldRejectNullTransport_whenTransportIsNull() {
        final GitHubInstallationAuthConfiguration configuration = configuration();

        final Throwable thrown = catchThrowable(() -> TestInstallationAuths.create(null, configuration));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("transport");
    }

    @Test
    void create_shouldRejectNullSettings_whenConfigurationIsNull() {
        final HttpTransport transport = HttpTransportMocks.mock();

        final Throwable thrown = catchThrowable(() -> TestInstallationAuths.create(transport, null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    @Test
    void create_shouldSendTokenRequestsThroughClientTransport_whenClientSendsRequestsToo() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any())).thenReturn(tokenResponse());
        final GitHubClient client = TestGitHubClients.raw(transport);
        final GitHubInstallationAuthenticationProvider auth = TestInstallationAuths.create(transport, configuration());
        final HttpResponse response;

        try (final GitHubClient clientUnderTest = client;
                final GitHubInstallationAuthenticationProvider authUnderTest = auth) {
            response = clientUnderTest.execute(HttpCall.get("/user"));
            authUnderTest.authentication().orElseThrow().value();
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(201);
            softly.assertThat(captor.getAllValues().get(1).url().encodedPath())
                    .isEqualTo("/app/installations/42/access_tokens");
            softly.assertThat(captor.getAllValues()
                            .get(1)
                            .headers()
                            .get(USER_AGENT)
                            .orElseThrow())
                    .isEqualTo(TestClients.USER_AGENT);
            softly.assertThat(captor.getAllValues()
                            .get(1)
                            .headers()
                            .get(AUTHORIZATION)
                            .orElseThrow())
                    .startsWith("Bearer ");
        });
    }

    @Test
    void close_shouldCloseTransport_whenAuthClosed() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final GitHubInstallationAuthenticationProvider auth = TestInstallationAuths.create(transport, configuration());

        auth.close();

        verify(transport, times(1)).close();
    }

    @Test
    void toString_shouldHideSecrets_whenAuthCreated() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final GitHubInstallationAuthenticationProvider auth = TestInstallationAuths.create(transport, configuration());

        final String text = auth.toString();

        assertThat(text).isEqualTo("GitHubInstallationAuthenticationProvider[appId=Iv1.test, installationId=42]");
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubInstallationAuthenticationProviders> constructor =
                GitHubInstallationAuthenticationProviders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void create_shouldSignAppJwtAtInjectedClock_whenClockGiven() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any())).thenReturn(tokenResponse());
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        try (GitHubInstallationAuthenticationProvider auth =
                TestInstallationAuths.create(transport, "Iv1.test", AppKeys.pkcs1Pem(), 42, clock)) {
            auth.authentication().orElseThrow().value();
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        assertThat(captor.getValue().headers().get(AUTHORIZATION).orElseThrow())
                .isEqualTo("Bearer "
                        + GitHubAppJwts.create("Iv1.test", AppKeys.pair().getPrivate(), NOW));
    }

    @Test
    void token_shouldRenewOnInjectedClock_whenRefreshPointPasses() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(
                        tokenResponse("ghs_first", NOW.plusSeconds(30)),
                        tokenResponse("ghs_second", NOW.plusSeconds(3600)));
        final MutableClock clock = new MutableClock(NOW);
        final String first;
        final String cached;
        final String renewed;

        try (GitHubInstallationAuthenticationProvider auth =
                TestInstallationAuths.create(transport, "Iv1.test", AppKeys.pkcs1Pem(), 42, clock)) {
            first = auth.authentication().orElseThrow().value();
            clock.set(NOW.plusSeconds(10));
            cached = auth.authentication().orElseThrow().value();
            clock.set(NOW.plusSeconds(20));
            renewed = auth.authentication().orElseThrow().value();
        }

        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo("ghs_first");
            softly.assertThat(cached).isEqualTo("ghs_first");
            softly.assertThat(renewed).isEqualTo("ghs_second");
        });
        verify(transport, times(2)).execute(any());
    }

    @Test
    void create_shouldBuildAuthFromPem_whenClockGiven() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final GitHubInstallationAuthenticationProvider auth = TestInstallationAuths.create(
                transport, "Iv1.test", AppKeys.pkcs1Pem(), 42, Clock.fixed(NOW, ZoneOffset.UTC));

        final String text = auth.toString();

        assertThat(text).isEqualTo("GitHubInstallationAuthenticationProvider[appId=Iv1.test, installationId=42]");
    }

    @Test
    void create_shouldThrow_whenClockNull() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final GitHubInstallationAuthConfiguration configuration = configuration();
        final Clock clock = null;

        final Throwable thrown = catchThrowable(() -> TestInstallationAuths.create(transport, configuration, clock));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }

    private static HttpResponse tokenResponse(final String token, final Instant expiresAt) {
        return new HttpResponse(
                201,
                HttpHeaders.of(CONTENT_TYPE, "application/json"),
                HttpBody.of(("{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse tokenResponse() {
        final String expiresAt = Instant.now().plusSeconds(3600).toString();
        return new HttpResponse(
                201,
                HttpHeaders.of(CONTENT_TYPE, "application/json"),
                HttpBody.of(("{\"token\":\"ghs_shared\",\"expires_at\":\"" + expiresAt + "\"}")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static GitHubInstallationAuthConfiguration configuration() {
        return GitHubInstallationAuthConfiguration.builder()
                .appId("Iv1.test")
                .privateKey(privateKey())
                .installationId(42)
                .build();
    }

    private static PrivateKey privateKey() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void create_shouldBuildAuthFromPem_whenAppIdAndPemGiven() {
        final HttpTransport transport = HttpTransportMocks.mock();
        final String pem = AppKeys.pkcs1Pem();

        final GitHubInstallationAuthenticationProvider auth =
                TestInstallationAuths.create(transport, "Iv1.test", pem, 42);

        assertThat(auth).hasToString("GitHubInstallationAuthenticationProvider[appId=Iv1.test, installationId=42]");
    }

    @Test
    void create_shouldRejectNullTransport_whenPemOverloadUsed() {
        final String pem = AppKeys.pkcs1Pem();

        final Throwable thrown = catchThrowable(() -> TestInstallationAuths.create(null, "Iv1.test", pem, 42));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("transport");
    }

    @Test
    void create_shouldRejectNullUserAgent_whenUserAgentIsNull() {
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.defaults();
        final GitHubInstallationAuthConfiguration configuration = configuration();

        final Throwable thrown = catchThrowable(
                () -> GitHubInstallationAuthenticationProviders.create(null, clientConfiguration, configuration));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("userAgent");
    }

    @Test
    void create_shouldRejectBlankUserAgent_whenUserAgentIsBlank() {
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.defaults();
        final GitHubInstallationAuthConfiguration configuration = configuration();

        final Throwable thrown = catchThrowable(
                () -> GitHubInstallationAuthenticationProviders.create(" ", clientConfiguration, configuration));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userAgent");
    }

    @Test
    void create_shouldRejectNullClientConfiguration_whenClientConfigurationIsNull() {
        final GitHubClientConfiguration clientConfiguration = null;
        final GitHubInstallationAuthConfiguration configuration = configuration();

        final Throwable thrown = catchThrowable(() -> GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, configuration));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clientConfiguration");
    }

    @Test
    void create_shouldRejectNullConfiguration_whenConfigurationIsNull() {
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.defaults();
        final GitHubInstallationAuthConfiguration configuration = null;

        final Throwable thrown = catchThrowable(() -> GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, configuration));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("configuration");
    }

    @Test
    void create_shouldRejectNullClock_whenClockIsNull() {
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.defaults();
        final GitHubInstallationAuthConfiguration configuration = configuration();
        final Clock clock = null;

        final Throwable thrown = catchThrowable(() -> GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, configuration, clock));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }

    @Test
    void create_shouldRejectNullAppId_whenAppIdIsNull() {
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.defaults();
        final String appId = null;

        final Throwable thrown = catchThrowable(() -> GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, appId, AppKeys.pkcs1Pem(), 42));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_shouldRejectNullPrivateKeyPem_whenPrivateKeyPemIsNull() {
        final GitHubClientConfiguration clientConfiguration = GitHubClientConfiguration.defaults();
        final String privateKeyPem = null;

        final Throwable thrown = catchThrowable(() -> GitHubInstallationAuthenticationProviders.create(
                TestClients.USER_AGENT, clientConfiguration, "Iv1.test", privateKeyPem, 42));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("pem");
    }
}
