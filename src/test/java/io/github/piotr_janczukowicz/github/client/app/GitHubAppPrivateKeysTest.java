package io.github.piotr_janczukowicz.github.client.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubAppPrivateKeysTest {

    @Test
    void parse_shouldReadPkcs8Pem() {
        final PrivateKey key = GitHubAppPrivateKeys.parse(AppKeys.pkcs8Pem());

        assertThat(key.getEncoded()).isEqualTo(AppKeys.pair().getPrivate().getEncoded());
    }

    @Test
    void parse_shouldReadPkcs1PemWhichIsWhatGitHubHandsOut() {
        final PrivateKey key = GitHubAppPrivateKeys.parse(AppKeys.pkcs1Pem());

        assertSoftly(softly -> {
            softly.assertThat(key.getAlgorithm()).isEqualTo("RSA");
            softly.assertThat(key.getEncoded())
                    .isEqualTo(AppKeys.pair().getPrivate().getEncoded());
        });
    }

    @Test
    void parse_shouldTolerateSurroundingWhitespaceAndWindowsLineEndings() {
        final String pem = "\n  " + AppKeys.pkcs1Pem().replace("\n", "\r\n") + "\n\n";

        assertThat(GitHubAppPrivateKeys.parse(pem).getEncoded())
                .isEqualTo(AppKeys.pair().getPrivate().getEncoded());
    }

    @Test
    void parse_shouldReject_whenTextIsNotAnUnencryptedPrivateKey() {
        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> GitHubAppPrivateKeys.parse("hello")))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> GitHubAppPrivateKeys.parse(
                            "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----")))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() ->
                            GitHubAppPrivateKeys.parse("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----")))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void parse_shouldExplainLiteralBackslashN_whenLineBreaksWereEscaped() {
        final String escaped = "-----BEGIN RSA PRIVATE KEY-----\\nAAAA\\n-----END RSA PRIVATE KEY-----";

        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(escaped));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line breaks")
                .message()
                .doesNotContain("AAAA");
    }

    @Test
    void parse_shouldExplainEncryptedKey_whenKeyIsEncrypted() {
        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(
                "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encrypted");
    }

    @Test
    void parse_shouldExplainEncryptedKey_whenLegacyPemHeaderSaysEncrypted() {
        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(
                "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nAAAA\n-----END RSA PRIVATE KEY-----"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encrypted");
    }

    @Test
    void parse_shouldExplainEncryptedKey_whenOnlyTheDekInfoHeaderIsPresent() {
        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(
                "-----BEGIN RSA PRIVATE KEY-----\nDEK-Info: AES-128-CBC,00\n\nAAAA\n-----END RSA PRIVATE KEY-----"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encrypted");
    }

    @Test
    void parse_shouldExplainEncryptedKey_whenLegacyPemHasWindowsLineBreaks() {
        final String pem = """
                -----BEGIN RSA PRIVATE KEY-----\r
                Proc-Type: 4,ENCRYPTED\r
                DEK-Info: AES-128-CBC,00\r
                \r
                AAAA\r
                -----END RSA PRIVATE KEY-----\r
                """;

        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(pem));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encrypted");
    }

    @Test
    void parse_shouldExplainSurroundingText_whenTextFollowsAPkcs8Key() {
        final Throwable thrown = catchThrowable(
                () -> GitHubAppPrivateKeys.parse("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\nnote"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("END line");
    }

    @Test
    void parse_shouldExplainSurroundingText_whenTextComesBeforeTheKey() {
        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(
                "my app key\n-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before the BEGIN line");
    }

    @Test
    void parse_shouldRejectInvalidBase64WithoutEchoingIt() {
        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(
                "-----BEGIN RSA PRIVATE KEY-----\n!!!secret-looking!!!\n-----END RSA PRIVATE KEY-----"));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64")
                .message()
                .doesNotContain("secret-looking");
    }

    @Test
    void parse_shouldReject_whenBytesAreNotAnRsaKey() throws Exception {
        final KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        final String ecPem =
                AppKeys.pem("PRIVATE KEY", ec.generateKeyPair().getPrivate().getEncoded());

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> GitHubAppPrivateKeys.parse(ecPem)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("valid RSA private key");
            softly.assertThat(catchThrowable(() -> GitHubAppPrivateKeys.parse(
                            "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("valid RSA private key");
        });
    }

    @Test
    void parse_shouldReject_whenHeaderAndFooterMismatched() {
        final String mismatched = """
                -----BEGIN RSA PRIVATE KEY-----
                AAAA
                -----END PRIVATE KEY-----""";

        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(mismatched));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldReject_whenTextNull() {
        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_shouldRejectInstantiation() throws Exception {
        final Constructor<GitHubAppPrivateKeys> constructor = GitHubAppPrivateKeys.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parse_shouldNotBlameEscapedLineBreaks_whenTheTextAlsoHasRealLineBreaks() {
        final String mixed = "-----BEGIN PUBLIC KEY-----\\nAAAA\n-----END PUBLIC KEY-----";

        final Throwable thrown = catchThrowable(() -> GitHubAppPrivateKeys.parse(mixed));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("escaped");
    }
}
