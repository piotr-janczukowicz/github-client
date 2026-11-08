package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.EncodingUtils.decodeBase64;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Reads the RSA private key of a GitHub App from PEM text.
 *
 * <p>GitHub hands out the key as PKCS#1 ({@code -----BEGIN RSA PRIVATE KEY-----}), which the JDK cannot read directly;
 * PKCS#8 ({@code -----BEGIN PRIVATE KEY-----}) is read as is. Encrypted keys are not supported. Error messages never
 * contain key material.
 */
public final class GitHubAppPrivateKeys {

    private static final String BEGIN = "-----BEGIN ";
    private static final String END = "-----END ";
    private static final String DASHES = "-----";
    private static final String PKCS1_LABEL = "RSA PRIVATE KEY";
    private static final String PKCS8_LABEL = "PRIVATE KEY";
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_OCTET_STRING = 0x04;
    private static final int SHORT_FORM_LIMIT = 0x80;
    private static final int BYTE_MASK = 0xff;
    private static final int BYTE_BITS = 8;

    private static final byte[] PKCS8_VERSION_AND_RSA_ALGORITHM = {
        0x02,
        0x01,
        0x00,
        0x30,
        0x0d,
        0x06,
        0x09,
        0x2a,
        (byte) 0x86,
        0x48,
        (byte) 0x86,
        (byte) 0xf7,
        0x0d,
        0x01,
        0x01,
        0x01,
        0x05,
        0x00
    };

    private GitHubAppPrivateKeys() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Parses a PEM encoded RSA private key.
     *
     * @param pem PEM text, PKCS#1 or PKCS#8
     * @return the key
     * @throws NullPointerException if {@code pem} is {@code null}
     * @throws IllegalArgumentException if the text is not an unencrypted RSA private key in PEM form
     */
    public static PrivateKey parse(final String pem) {
        requireNonNull(pem, "pem");
        final String text = pem.strip();
        if (hasLiteralLineBreaks(text)) {
            throw new IllegalArgumentException(
                    "the PEM text has the two characters \\n where line breaks belong; replace them with real newlines");
        }
        if (isEncrypted(text)) {
            throw new IllegalArgumentException(
                    "the private key is encrypted, which is not supported; decrypt it first");
        }
        final byte[] pkcs8;
        if (isLabeled(text, PKCS1_LABEL)) {
            pkcs8 = pkcs1ToPkcs8(body(text, PKCS1_LABEL));
        } else if (isLabeled(text, PKCS8_LABEL)) {
            pkcs8 = body(text, PKCS8_LABEL);
        } else {
            throw new IllegalArgumentException(notAnUnencryptedKey(text));
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (final GeneralSecurityException invalid) {
            // Only the message is kept out of the text: the cause names the parsing step, not the key bytes.
            throw new IllegalArgumentException("the PEM text is not a valid RSA private key", invalid);
        }
    }

    private static boolean hasLiteralLineBreaks(final String text) {
        return text.contains("\\n") && text.indexOf('\n') < 0;
    }

    // The messages are fixed text: they never quote the input, which may be key material.
    private static String notAnUnencryptedKey(final String text) {
        if (text.contains(BEGIN + PKCS1_LABEL + DASHES) || text.contains(BEGIN + PKCS8_LABEL + DASHES)) {
            return "the PEM text has other text before the BEGIN line or after the END line; "
                    + "it must hold the key block only";
        }
        return "not an unencrypted PEM private key; expected BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY";
    }

    // An encrypted PKCS#8 key has its own label; a legacy encrypted one keeps the RSA label and adds headers.
    private static boolean isEncrypted(final String text) {
        return text.contains(BEGIN + "ENCRYPTED PRIVATE KEY" + DASHES)
                || text.contains("Proc-Type:")
                || text.contains("DEK-Info:");
    }

    private static boolean isLabeled(final String text, final String label) {
        return text.startsWith(BEGIN + label + DASHES) && text.endsWith(END + label + DASHES);
    }

    private static byte[] body(final String text, final String label) {
        final String base64 = text.substring(
                        (BEGIN + label + DASHES).length(), text.length() - (END + label + DASHES).length())
                .replaceAll("\\s", "");
        return decodeBase64(base64, "the PEM text is not valid base64");
    }

    private static byte[] pkcs1ToPkcs8(final byte[] pkcs1) {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(PKCS8_VERSION_AND_RSA_ALGORITHM);
        content.writeBytes(der(TAG_OCTET_STRING, pkcs1));
        return der(TAG_SEQUENCE, content.toByteArray());
    }

    private static byte[] der(final int tag, final byte[] content) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (content.length < SHORT_FORM_LIMIT) {
            out.write(content.length);
        } else {
            int lengthBytes = 0;
            for (int rest = content.length; rest > 0; rest >>>= BYTE_BITS) {
                lengthBytes++;
            }
            out.write(SHORT_FORM_LIMIT | lengthBytes);
            for (int shift = (lengthBytes - 1) * BYTE_BITS; shift >= 0; shift -= BYTE_BITS) {
                out.write((content.length >>> shift) & BYTE_MASK);
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }
}
