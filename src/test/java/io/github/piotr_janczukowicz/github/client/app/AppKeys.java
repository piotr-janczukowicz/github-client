package io.github.piotr_janczukowicz.github.client.app;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

final class AppKeys {

    private static final int PKCS1_OFFSET_IN_2048_BIT_PKCS8 = 26;
    private static final int OCTET_STRING_OFFSET_IN_2048_BIT_PKCS8 = 22;

    private static final KeyPair PAIR = generate();

    private AppKeys() {
        throw new UnsupportedOperationException("Utility class");
    }

    static KeyPair pair() {
        return PAIR;
    }

    static String pkcs8Pem() {
        return pem("PRIVATE KEY", PAIR.getPrivate().getEncoded());
    }

    static String pkcs1Pem() {
        final byte[] pkcs8 = PAIR.getPrivate().getEncoded();
        if (pkcs8[OCTET_STRING_OFFSET_IN_2048_BIT_PKCS8] != 0x04
                || pkcs8[OCTET_STRING_OFFSET_IN_2048_BIT_PKCS8 + 1] != (byte) 0x82) {
            throw new IllegalStateException("unexpected PKCS#8 layout");
        }
        return pem("RSA PRIVATE KEY", Arrays.copyOfRange(pkcs8, PKCS1_OFFSET_IN_2048_BIT_PKCS8, pkcs8.length));
    }

    static String pem(final String label, final byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    private static KeyPair generate() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
