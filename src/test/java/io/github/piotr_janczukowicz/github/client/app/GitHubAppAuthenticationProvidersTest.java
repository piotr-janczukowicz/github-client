package io.github.piotr_janczukowicz.github.client.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubAppAuthenticationProvidersTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String APP_ID = "Iv1.test";
    private static final String APP_IDENTITY = "app:Iv1.test";

    @Test
    void app_shouldNameAppAndSignBearerJwt_whenPemGiven() {
        final GitHubAppAuthenticationProvider auth = GitHubAppAuthenticationProviders.app(APP_ID, AppKeys.pkcs1Pem());

        final Authentication authentication = auth.authentication().orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(authentication.identity()).isEqualTo(APP_IDENTITY);
            softly.assertThat(authentication.headerValue()).startsWith("Bearer ");
        });
    }

    @Test
    void app_shouldSignWithSystemClock_whenPemGiven() {
        final GitHubAppAuthenticationProvider auth = GitHubAppAuthenticationProviders.app(APP_ID, AppKeys.pkcs8Pem());

        final String authorization = auth.authentication().orElseThrow().headerValue();

        assertThat(authorization).startsWith("Bearer ");
    }

    @Test
    void app_shouldNameApp_whenPemGiven() {
        final GitHubAppAuthenticationProvider auth = GitHubAppAuthenticationProviders.app(APP_ID, AppKeys.pkcs1Pem());

        final String identity = auth.authentication().orElseThrow().identity();

        assertThat(identity).isEqualTo(APP_IDENTITY);
    }

    @Test
    void app_shouldNameAppAndSignClaimsFromClock_whenPemAndClockGiven() {
        final GitHubAppAuthenticationProvider auth =
                GitHubAppAuthenticationProviders.app(APP_ID, AppKeys.pkcs1Pem(), Clock.fixed(NOW, ZoneOffset.UTC));

        final Authentication authentication = auth.authentication().orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(authentication.identity()).isEqualTo(APP_IDENTITY);
            softly.assertThat(authentication.headerValue()).startsWith("Bearer ");
            softly.assertThat(payload(authentication.headerValue())).isEqualTo(claims(NOW));
        });
    }

    @Test
    void app_shouldSignWithPemKeyAndClock_whenPemAndClockGiven() {
        final GitHubAppAuthenticationProvider auth =
                GitHubAppAuthenticationProviders.app(APP_ID, AppKeys.pkcs1Pem(), Clock.fixed(NOW, ZoneOffset.UTC));

        final String authorization = auth.authentication().orElseThrow().headerValue();

        assertThat(authorization)
                .isEqualTo(
                        "Bearer " + GitHubAppJwts.create(APP_ID, AppKeys.pair().getPrivate(), NOW));
    }

    @Test
    void app_shouldSignWithPrivateKeyAndClock_whenKeyAndClockGiven() {
        final GitHubAppAuthenticationProvider auth = GitHubAppAuthenticationProviders.app(
                APP_ID, AppKeys.pair().getPrivate(), Clock.fixed(NOW, ZoneOffset.UTC));

        final String authorization = auth.authentication().orElseThrow().headerValue();

        assertThat(authorization)
                .isEqualTo(
                        "Bearer " + GitHubAppJwts.create(APP_ID, AppKeys.pair().getPrivate(), NOW));
    }

    @Test
    void app_shouldThrow_whenPemNull() {
        final String pem = null;

        final Throwable thrown =
                catchThrowable(() -> GitHubAppAuthenticationProviders.app(APP_ID, pem, Clock.systemUTC()));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("privateKeyPem");
    }

    @Test
    void app_shouldBuildAuthWithAppIdOnly_whenPrivateKeyGiven() {
        final GitHubAppAuthenticationProvider auth =
                GitHubAppAuthenticationProviders.app(APP_ID, AppKeys.pair().getPrivate());

        final String text = auth.toString();

        assertThat(text).isEqualTo("GitHubAppAuthenticationProvider[appId=Iv1.test]");
    }

    @Test
    void app_shouldNameApp_whenPrivateKeyGiven() {
        final GitHubAppAuthenticationProvider auth =
                GitHubAppAuthenticationProviders.app(APP_ID, AppKeys.pair().getPrivate());

        final String identity = auth.authentication().orElseThrow().identity();

        assertThat(identity).isEqualTo(APP_IDENTITY);
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubAppAuthenticationProviders> constructor =
                GitHubAppAuthenticationProviders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
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
