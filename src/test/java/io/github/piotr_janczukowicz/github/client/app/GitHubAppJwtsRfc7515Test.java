package io.github.piotr_janczukowicz.github.client.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubAppJwtsRfc7515Test {

    private static final String N = "ofgWCuLjybRlzo0tZWJjNiuSfb4p4fAkd_wWJcyQoTbji9k0l8W26mPddxHm"
            + "fHQp-Vaw-4qPCJrcS2mJPMEzP1Pt0Bm4d4QlL-yRT-SFd2lZS-pCgNMsD1W_"
            + "YpRPEwOWvG6b32690r2jZ47soMZo9wGzjb_7OMg0LOL-bSf63kpaSHSXndS5"
            + "z5rexMdbBYUsLA9e-KXBdQOS-UTo7WTBEMa2R2CapHg665xsmtdVMTBQY4uD"
            + "Zlxvb3qCo5ZwKh9kG4LT6_I5IhlJH7aGhyxXFvUK-DWNmoudF8NAco9_h9ia"
            + "GNj8q2ethFkMLs91kzk2PAcDTW9gb54h4FRWyuXpoQ";
    private static final String E = "AQAB";
    private static final String D = "Eq5xpGnNCivDflJsRQBXHx1hdR1k6Ulwe2JZD50LpXyWPEAeP88vLNO97Ijl"
            + "A7_GQ5sLKMgvfTeXZx9SE-7YwVol2NXOoAJe46sui395IW_GO-pWJ1O0BkTG"
            + "oVEn2bKVRUCgu-GjBVaYLU6f3l9kJfFNS3E0QbVdxzubSu3Mkqzjkn439X0M"
            + "_V51gfpRLI9JYanrC4D4qAdGcopV_0ZHHzQlBjudU2QvXt4ehNYTCBr6XCLQ"
            + "UShb1juUO1ZdiYoFaFQT5Tw8bGUl_x_jTj3ccPDVZFD9pIuhLhBOneufuBiB"
            + "4cS98l2SR_RQyGWSeWjnczT0QU91p1DhOVRuOopznQ";
    private static final String P = "4BzEEOtIpmVdVEZNCqS7baC4crd0pqnRH_5IB3jw3bcxGn6QLvnEtfdUdiYr"
            + "qBdss1l58BQ3KhooKeQTa9AB0Hw_Py5PJdTJNPY8cQn7ouZ2KKDcmnPGBY5t"
            + "7yLc1QlQ5xHdwW1VhvKn-nXqhJTBgIPgtldC-KDV5z-y2XDwGUc";
    private static final String Q = "uQPEfgmVtjL0Uyyx88GZFF1fOunH3-7cepKmtH4pxhtCoHqpWmT8YAmZxaew"
            + "HgHAjLYsp1ZSe7zFYHj7C6ul7TjeLQeZD_YwD66t62wDmpe_HlB-TnBA-njb"
            + "glfIsRLtXlnDzQkv5dTltRJ11BKBBypeeF6689rjcJIDEz9RWdc";
    private static final String DP = "BwKfV3Akq5_MFZDFZCnW-wzl-CCo83WoZvnLQwCTeDv8uzluRSnm71I3QCLd"
            + "hrqE2e9YkxvuxdBfpT_PI7Yz-FOKnu1R6HsJeDCjn12Sk3vmAktV2zb34MCd"
            + "y7cpdTh_YVr7tss2u6vneTwrA86rZtu5Mbr1C1XsmvkxHQAdYo0";
    private static final String DQ = "h_96-mK1R_7glhsum81dZxjTnYynPbZpHziZjeeHcXYsXaaMwkOlODsWa7I9"
            + "xXDoRwbKgB719rrmI2oKr6N3Do9U0ajaHF-NKJnwgjMd2w9cjz3_-kyNlxAr"
            + "2v4IKhGNpmM5iIgOS1VZnOZ68m6_pbLBSp3nssTdlqvd0tIiTHU";
    private static final String QI = "IYd7DHOhrWvxkwPQsRM2tOgrjbcrfvtQJipd-DlcxyVuuM9sQLdgjVk2oy26"
            + "F0EmpScGLq2MowX7fhd_QJQ3ydy5cY7YIBi87w93IKLEdfnbJtoOPLUW0ITr"
            + "JReOgo1cq9SbsxYawBgfp_gh6A5603k2-ZQwVK0JKSHuLFkuQ3U";
    private static final String SIGNING_INPUT = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTk"
            + "zODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ";
    private static final String EXPECTED_SIGNATURE = "cC4hiUPoj9Eetdgtv3hF80EGrhuB__dzERat0XF9g2VtQgr9PJbu3XOiZj5R"
            + "Zmh7AAuHIm4Bh-0Qc_lF5YKt_O8W2Fp5jujGbds9uJdbF9CUAr7t1dnZcAcQ"
            + "jbKBYNX4BAynRFdiuB--f_nZLgrnbyTyWzO75vRK5h6xBArLIARNPvkSjtQB"
            + "MHlb1L07Qe7K0GarZRmB_eSN9383LcOLn6_dO--xi12jzDwusC-eOkHWEsqt"
            + "FZESc6BfI7noOPqvhJ1phCnvWh6IeYI2w9QOYEUipUTI8np6LbgGY9Fs98rq"
            + "Vt5AXLIhWkWywlVmtVrBp0igcN_IoypGlUPQGe77Rw";
    private static final int RSA_2048_PKCS8_PREFIX_LENGTH = 26;

    @Test
    void sign_shouldReproduceTheRfcSignature_whenKeyIsBuiltFromTheJwkParameters() throws Exception {
        final PrivateKey key = rfcKeyFromJwkIntegers();

        assertThat(GitHubAppJwts.sign(SIGNING_INPUT, key)).isEqualTo(SIGNING_INPUT + "." + EXPECTED_SIGNATURE);
    }

    @Test
    void sign_shouldReproduceTheRfcSignature_whenKeyIsReadFromPkcs1Pem() throws Exception {
        final PrivateKey fromPem = GitHubAppPrivateKeys.parse(pkcs1Pem(rfcKeyFromJwkIntegers()));

        assertThat(GitHubAppJwts.sign(SIGNING_INPUT, fromPem)).isEqualTo(SIGNING_INPUT + "." + EXPECTED_SIGNATURE);
    }

    @Test
    void sign_shouldReproduceTheRfcSignature_whenKeyIsReadFromPkcs8Pem() throws Exception {
        final PrivateKey fromPem = GitHubAppPrivateKeys.parse(
                AppKeys.pem("PRIVATE KEY", rfcKeyFromJwkIntegers().getEncoded()));

        assertThat(GitHubAppJwts.sign(SIGNING_INPUT, fromPem)).isEqualTo(SIGNING_INPUT + "." + EXPECTED_SIGNATURE);
    }

    private static PrivateKey rfcKeyFromJwkIntegers() throws Exception {
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new RSAPrivateCrtKeySpec(
                        integer(N),
                        integer(E),
                        integer(D),
                        integer(P),
                        integer(Q),
                        integer(DP),
                        integer(DQ),
                        integer(QI)));
    }

    private static BigInteger integer(final String base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
    }

    private static String pkcs1Pem(final PrivateKey key) {
        final byte[] pkcs8 = key.getEncoded();
        assertThat(pkcs8[22]).isEqualTo((byte) 0x04);
        return AppKeys.pem("RSA PRIVATE KEY", Arrays.copyOfRange(pkcs8, RSA_2048_PKCS8_PREFIX_LENGTH, pkcs8.length));
    }
}
