package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.github.client.exception.GitHubUnauthorizedException;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
class GitHubAppAuthenticationProviderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String APP_ID = "Iv1.test";
    private static final String APP_IDENTITY = "app:Iv1.test";

    @Test
    void execute_shouldSendJwtWithClockClaims_whenGetApp() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any())).thenReturn(ok());
        final GitHubAppAuthenticationProvider auth = new DefaultGitHubAppAuthenticationProvider(
                APP_ID, AppKeys.pair().getPrivate(), Clock.fixed(NOW, ZoneOffset.UTC));
        final HttpCall request = HttpCall.get("/app");

        try (GitHubClient app = appClient(transport, auth)) {
            app.execute(request);
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        final String authorization =
                captor.getValue().headers().get(AUTHORIZATION).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(authorization).startsWith("Bearer ");
            softly.assertThat(payload(authorization)).isEqualTo(claims(NOW));
        });
    }

    @Test
    void execute_shouldShareCredentialIdentity_whenJwtsDiffer() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any())).thenReturn(ok());
        final MutableClock clock = new MutableClock(NOW);
        final HttpCall request = HttpCall.get("/app");

        try (GitHubClient app = appClient(
                transport,
                new DefaultGitHubAppAuthenticationProvider(
                        APP_ID, AppKeys.pair().getPrivate(), clock))) {
            app.execute(request);
            clock.advance(Duration.ofMinutes(1));
            app.execute(request);
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        final HttpRequest first = captor.getAllValues().get(0);
        final HttpRequest second = captor.getAllValues().get(1);
        assertSoftly(softly -> {
            softly.assertThat(first.headers().get(AUTHORIZATION))
                    .isNotEqualTo(second.headers().get(AUTHORIZATION));
            softly.assertThat(first.credentialId()).isEqualTo(APP_IDENTITY);
            softly.assertThat(second.credentialId()).isEqualTo(APP_IDENTITY);
            softly.assertThat(second.credentialKey()).isEqualTo(first.credentialKey());
        });
    }

    @Test
    void execute_shouldNotResend_whenGitHubRejectsJwt() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any())).thenReturn(unauthorized());
        final HttpCall request = HttpCall.get("/app");

        final Throwable thrown;
        try (GitHubClient app = appClient(
                transport,
                new DefaultGitHubAppAuthenticationProvider(
                        APP_ID, AppKeys.pair().getPrivate(), Clock.fixed(NOW, ZoneOffset.UTC)))) {
            thrown = catchThrowable(() -> app.execute(request));
        }

        verify(transport, times(1)).execute(any());
        assertThat(thrown).isInstanceOf(GitHubUnauthorizedException.class);
    }

    @Test
    void isRenewableAfterRejection_shouldReturnFalse_whenCalled() {
        final GitHubAppAuthenticationProvider auth = new DefaultGitHubAppAuthenticationProvider(
                APP_ID, AppKeys.pair().getPrivate(), Clock.fixed(NOW, ZoneOffset.UTC));

        final boolean renewable = auth.isRenewableAfterRejection(Authentication.ofBearer("header.payload.signature"));

        assertThat(renewable).isFalse();
    }

    @Test
    void authentication_shouldNameApp_whenCalled() {
        final GitHubAppAuthenticationProvider auth = new DefaultGitHubAppAuthenticationProvider(
                APP_ID, AppKeys.pair().getPrivate(), Clock.fixed(NOW, ZoneOffset.UTC));

        final Optional<String> identity = auth.authentication().map(Authentication::identity);

        assertThat(identity).contains(APP_IDENTITY);
    }

    @Test
    void toString_shouldShowOnlyAppId_whenCalled() {
        final GitHubAppAuthenticationProvider auth = new DefaultGitHubAppAuthenticationProvider(
                APP_ID, AppKeys.pair().getPrivate(), Clock.fixed(NOW, ZoneOffset.UTC));

        final String text = auth.toString();

        assertThat(text).isEqualTo("GitHubAppAuthenticationProvider[appId=Iv1.test]");
    }

    @Test
    void constructor_shouldThrow_whenAppIdHasOtherCharacters() {
        final PrivateKey key = AppKeys.pair().getPrivate();

        final Throwable thrown =
                catchThrowable(() -> new DefaultGitHubAppAuthenticationProvider("a b", key, Clock.systemUTC()));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appId must match the required pattern");
    }

    @Test
    void constructor_shouldThrow_whenKeyNull() {
        final PrivateKey key = null;

        final Throwable thrown =
                catchThrowable(() -> new DefaultGitHubAppAuthenticationProvider(APP_ID, key, Clock.systemUTC()));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("key");
    }

    @Test
    void constructor_shouldThrow_whenClockNull() {
        final Clock clock = null;

        final Throwable thrown = catchThrowable(() -> new DefaultGitHubAppAuthenticationProvider(
                APP_ID, AppKeys.pair().getPrivate(), clock));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }

    private static GitHubClient appClient(final HttpTransport transport, final GitHubAppAuthenticationProvider auth) {
        return TestGitHubClients.raw(transport, TestClients.configuration(TestClients.GITHUB_BASE), auth);
    }

    private static HttpResponse ok() {
        return HttpResponses.of(HttpStatus.OK.code(), Map.of(), HttpBody.absent());
    }

    private static HttpResponse unauthorized() {
        return HttpResponses.of(
                HttpStatus.UNAUTHORIZED.code(),
                Map.of(),
                HttpBody.of("{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8)));
    }

    private static String claims(final Instant now) {
        return "{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":" + (now.getEpochSecond() + 540)
                + ",\"iss\":\"Iv1.test\"}";
    }

    private static String payload(final String authorization) {
        final String jwt = authorization.substring("Bearer ".length());
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }
}
