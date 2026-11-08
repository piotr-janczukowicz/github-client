package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.client.HttpOriginGuard;
import io.github.piotr_janczukowicz.http.uri.ParsedUrl;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** API origin plus GitHub upload hosts ({@code uploads.github.com} / GHES). The management console is not allowed. */
final class GitHubOriginGuard extends HttpOriginGuard {

    private static final String HTTPS = "https";

    private final GitHubApiBase apiBase;

    GitHubOriginGuard(final URI baseUri) {
        super(baseUri);
        apiBase = new GitHubApiBase(baseUri);
    }

    @Override
    public ParsedUrl requireTarget(final HttpMethod method, final URI uri, final @Nullable ParsedUrl parsed) {
        requireNonNull(method, "method");
        requireNonNull(uri, "uri");
        if (method == HttpMethod.POST
                && parsed != null
                && (isUploadsOrigin(parsed) || !hasDotSegments(uri) && isGhesApiUploadsPath(parsed))) {
            return parsed;
        }
        return super.requireTarget(method, uri, parsed);
    }

    @Override
    public ParsedUrl requireTarget(final HttpMethod method, final ParsedUrl url) {
        requireNonNull(method, "method");
        if (method == HttpMethod.POST) {
            return allowUploadPost(url);
        }
        return requireApiTarget(url);
    }

    /**
     * Allows {@code url} as a POST target: an uploads origin, a GHES {@code /api/uploads/} path, or an API target.
     * {@code url} is already normalised, so a {@code .} or {@code ..} segment, literal or percent-encoded, has been
     * resolved and cannot lead out of the checked prefix; the raw-URI overload checks dot segments itself.
     *
     * @param url absolute URL
     * @return {@code url}
     * @throws NullPointerException if {@code url} is {@code null}
     * @throws io.github.piotr_janczukowicz.http.client.HttpOriginViolationException if {@code url} is not an allowed
     *     target
     */
    ParsedUrl allowUploadPost(final ParsedUrl url) {
        requireNonNull(url, "url");
        if (isUploadsOrigin(url) || isGhesApiUploadsPath(url)) {
            return url;
        }
        return requireApiTarget(url);
    }

    private boolean isUploadsOrigin(final ParsedUrl actual) {
        final String uploadsHost = uploadsHost();
        return uploadsHost != null
                && HTTPS.equalsIgnoreCase(scheme)
                && port == 443
                && scheme.equalsIgnoreCase(actual.scheme())
                && uploadsHost.equalsIgnoreCase(actual.host())
                && port == actual.port();
    }

    private boolean isGhesApiUploadsPath(final ParsedUrl actual) {
        return !isOffOrigin(actual) && apiBase.isGhesUploadsPath(actual.encodedPath());
    }

    private @Nullable String uploadsHost() {
        return apiBase.uploadsHost();
    }
}
