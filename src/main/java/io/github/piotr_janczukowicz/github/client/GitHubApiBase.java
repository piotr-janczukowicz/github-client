package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The shape of the API base URI of a client: github.com, GitHub Enterprise Cloud with data residency
 * ({@code api.SUBDOMAIN.ghe.com}) or a GitHub Enterprise Server API ({@code https://HOST/api/v3/}). The one place that
 * knows where the uploads and the management console of such a host are.
 *
 * @param uri base URI of a client
 */
// S1075: these paths are fixed parts of GitHub Enterprise Server's API, not deployment settings; a host
// cannot serve them anywhere else, so a configurable parameter would only allow wrong values.
@SuppressWarnings("java:S1075")
record GitHubApiBase(URI uri) {

    static final String GHES_API_PATH = "/api/v3/";
    static final String GHES_UPLOADS_PATH = "/api/uploads/";
    static final String MANAGE_PATH = "/manage/v1/";

    private static final String GITHUB_API_HOST = "api.github.com";
    private static final String GITHUB_UPLOADS_HOST = "uploads.github.com";
    private static final String DATA_RESIDENCY_API_PREFIX = "api.";
    private static final String DATA_RESIDENCY_SUFFIX = ".ghe.com";
    private static final String UPLOADS_HOST_PREFIX = "uploads.";

    GitHubApiBase {
        requireNonNull(uri, "uri");
    }

    /**
     * Whether the base is the API of a GitHub Enterprise Server host, {@code https://HOST/api/v3/}: the only form whose
     * console and uploads this library knows.
     *
     * @return {@code true} for a GitHub Enterprise Server API base URI
     */
    boolean isGhes() {
        return GHES_API_PATH.equals(uri.getPath());
    }

    /**
     * The host that takes uploads of this API: {@code uploads.github.com} for github.com,
     * {@code uploads.SUBDOMAIN.ghe.com} for {@code api.SUBDOMAIN.ghe.com}.
     *
     * @return the uploads host, or {@code null} if the host has no separate uploads host
     */
    @Nullable String uploadsHost() {
        final String host = uri.getHost();
        if (host == null) {
            return null;
        }
        if (GITHUB_API_HOST.equalsIgnoreCase(host)) {
            return GITHUB_UPLOADS_HOST;
        }
        final String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith(DATA_RESIDENCY_API_PREFIX)
                && lower.endsWith(DATA_RESIDENCY_SUFFIX)
                && lower.length() > DATA_RESIDENCY_API_PREFIX.length() + DATA_RESIDENCY_SUFFIX.length()) {
            return UPLOADS_HOST_PREFIX + host.substring(DATA_RESIDENCY_API_PREFIX.length());
        }
        return null;
    }

    /**
     * The management console of a GitHub Enterprise Server host, {@code https://HOST/manage/v1/}, on the port of the
     * API.
     *
     * @return the console base URI, or empty if the base is not a GitHub Enterprise Server API
     */
    Optional<URI> consoleUri() {
        return isGhes() ? Optional.of(uri.resolve(MANAGE_PATH)) : Optional.empty();
    }

    /**
     * Whether {@code encodedPath} is below the uploads path of a GitHub Enterprise Server host.
     *
     * @param encodedPath encoded path of a URL
     * @return {@code true} for a GitHub Enterprise Server base and a path below {@code /api/uploads/}
     */
    boolean isGhesUploadsPath(final String encodedPath) {
        return isGhes() && encodedPath.startsWith(GHES_UPLOADS_PATH);
    }
}
