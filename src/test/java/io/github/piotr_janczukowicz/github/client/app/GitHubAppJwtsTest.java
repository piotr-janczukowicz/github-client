package io.github.piotr_janczukowicz.github.client.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubAppJwtsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void create_shouldProduceRs256TokenThatVerifiesWithThePublicKey() throws Exception {
        final String jwt = GitHubAppJwts.create("Iv1.abc123", AppKeys.pair().getPrivate(), NOW);

        final String[] parts = jwt.split("\\.");
        final Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(AppKeys.pair().getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        final boolean signatureValid = verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
        assertSoftly(softly -> {
            softly.assertThat(parts).hasSize(3);
            softly.assertThat(decode(parts[0])).isEqualTo("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
            softly.assertThat(signatureValid).isTrue();
        });
    }

    @Test
    void create_shouldBackdateIssuedAtAndExpireWithinGitHubsLimit() {
        final String jwt = GitHubAppJwts.create("12345", AppKeys.pair().getPrivate(), NOW);

        final String payload = decode(jwt.split("\\.")[1]);

        assertThat(payload)
                .isEqualTo("{\"iat\":" + (NOW.getEpochSecond() - 60) + ",\"exp\":" + (NOW.getEpochSecond() + 540)
                        + ",\"iss\":\"12345\"}");
    }

    @Test
    void create_shouldReject_whenKeyCannotSignWithRsa() throws Exception {
        final KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);

        final Throwable thrown = catchThrowable(
                () -> GitHubAppJwts.create("1", ec.generateKeyPair().getPrivate(), NOW));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RS256");
    }

    @Test
    void create_shouldRejectAppId_whenItCouldBreakTheJsonClaim() {
        final PrivateKey key = AppKeys.pair().getPrivate();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> GitHubAppJwts.create("a\",\"exp\":1", key, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> GitHubAppJwts.create("", key, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void constructor_shouldRejectInstantiation() throws Exception {
        final Constructor<GitHubAppJwts> constructor = GitHubAppJwts.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    private static String decode(final String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Test
    void requireAppId_shouldReturnAppId_whenItHasOnlyAllowedCharacters() {
        final String appId = "Iv1.abc_123-x";

        final String result = GitHubAppJwts.requireAppId(appId);

        assertThat(result).isEqualTo(appId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "abc\n", "a\"b"})
    void requireAppId_shouldThrow_whenItHasOtherCharacters(final String appId) {
        final Throwable thrown = catchThrowable(() -> GitHubAppJwts.requireAppId(appId));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appId must match the required pattern");
    }

    @Test
    void requireAppId_shouldThrow_whenNull() {
        final String appId = null;

        final Throwable thrown = catchThrowable(() -> GitHubAppJwts.requireAppId(appId));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }
}
