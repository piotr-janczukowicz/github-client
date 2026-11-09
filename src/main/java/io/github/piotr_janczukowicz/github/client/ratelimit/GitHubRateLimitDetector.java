package io.github.piotr_janczukowicz.github.client.ratelimit;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.transport.RateLimitDetector;
import io.github.piotr_janczukowicz.http.transport.RateLimitSignal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Lets the generic retry layer wait out GitHub rate limits. Classification is that of
 * {@link GitHubRateLimitInfo#from(HttpResponse, Clock)}: a 429, or a 403 whose error message names a secondary limit,
 * whose {@code X-RateLimit-Remaining} is {@code 0}, or that has a positive {@code Retry-After} with
 * {@code X-RateLimit-*} headers; a 403 that is only a permission error, or only has a {@code Retry-After} from a proxy,
 * is left alone.
 *
 * <p>What the signal asks for follows GitHub's documentation. A primary limit carries both its reset instant and its
 * {@code Retry-After} (if any), and the retry layer waits for the later one. A secondary limit carries
 * {@code Retry-After} if there is one, else the reset instant if {@code X-RateLimit-Remaining} is {@code 0}, else
 * nothing, which makes the retry layer wait at least a minute and longer for each repetition.
 *
 * <p><strong>Buckets.</strong> A primary limit is bound to one bucket, and a secondary limit to all of them (it covers
 * concurrency, request points, CPU time and content creation of the whole account). The bucket of a primary limit is
 * the {@code X-RateLimit-Resource} that GitHub names, when that name is one of the bucket names listed below;
 * otherwise, when the header is missing or names a resource this detector does not know, it is
 * {@link #bucketOf(HttpRequest)} of the request. So an exhausted {@code search} quota does not hold back {@code core}
 * requests, and a mismatch between the header and the path blocks the header's name, and the path's name too when the
 * header's name is unknown. Without a request ({@link #detect(HttpResponse, Clock)}), a primary limit names only a
 * known header resource and otherwise applies to every bucket.
 *
 * <p>{@link #bucketOf(HttpRequest)} maps a request to the resource that GitHub counts it against. The names are
 * {@code core}, {@code search}, {@code code_search} ({@code /search/code}), {@code graphql}, {@code rate_limit}
 * ({@code /rate_limit}, which GitHub does not count against any quota) and the families of the documented resources:
 * {@code code_scanning_upload} ({@code POST /repos/{owner}/{repo}/code-scanning/sarifs}), {@code code_scanning_autofix}
 * ({@code /repos/{owner}/{repo}/code-scanning/alerts/{number}/autofix}), {@code dependency_snapshots} ({@code POST
 * /repos/{owner}/{repo}/dependency-graph/snapshots}), {@code dependency_sbom}
 * ({@code /repos/{owner}/{repo}/dependency-graph/sbom}), {@code actions_runner_registration}
 * ({@code .../actions/runners/ registration-token} and {@code .../remove-token} under a repository, organization or
 * enterprise), {@code scim} ({@code /scim/v2/...}), {@code integration_manifest} ({@code POST
 * /app-manifests/{code}/conversions}), {@code source_import} ({@code /repos/{owner}/{repo}/import...}) and
 * {@code audit_log} ({@code /orgs/{org}/audit-log} and {@code /enterprises/{enterprise}/audit-log}). Other requests are
 * {@code core}. A GitHub Enterprise Server {@code /api/v3} prefix is ignored.
 *
 * <p><strong>Proactive block.</strong> A response that was not rejected can still spend the quota: a 2xx or 3xx (a
 * {@code 304} included) with {@code X-RateLimit-Remaining: 0} and a reset instant in the future. That is reported by
 * {@link #detectExhaustion(HttpResponse, HttpRequest, Clock)} with the reset as its instant and no {@code Retry-After},
 * so that the retry layer holds back the bucket until the reset, as it does after a rejection, and the response itself
 * is returned unchanged.
 */
public enum GitHubRateLimitDetector implements RateLimitDetector {
    INSTANCE;

    private static final String REQUEST_PARAMETER = "request";
    private static final String CORE = "core";
    private static final String SEARCH = "search";
    private static final String CODE_SEARCH = "code_search";
    private static final String GRAPHQL = "graphql";
    private static final String RATE_LIMIT = "rate_limit";
    private static final String CODE_SCANNING_UPLOAD = "code_scanning_upload";
    private static final String CODE_SCANNING_AUTOFIX = "code_scanning_autofix";
    private static final String DEPENDENCY_SNAPSHOTS = "dependency_snapshots";
    private static final String DEPENDENCY_SBOM = "dependency_sbom";
    private static final String ACTIONS_RUNNER_REGISTRATION = "actions_runner_registration";
    private static final String SCIM = "scim";
    private static final String INTEGRATION_MANIFEST = "integration_manifest";
    private static final String SOURCE_IMPORT = "source_import";
    private static final String AUDIT_LOG = "audit_log";

    /** Every name {@link #bucketOf(HttpRequest)} can return; a header naming another resource is not one. */
    static final Set<String> BUCKETS = Set.of(
            CORE,
            SEARCH,
            CODE_SEARCH,
            GRAPHQL,
            RATE_LIMIT,
            CODE_SCANNING_UPLOAD,
            CODE_SCANNING_AUTOFIX,
            DEPENDENCY_SNAPSHOTS,
            DEPENDENCY_SBOM,
            ACTIONS_RUNNER_REGISTRATION,
            SCIM,
            INTEGRATION_MANIFEST,
            SOURCE_IMPORT,
            AUDIT_LOG);

    private static final List<Family> FAMILIES = List.of(
            family(CODE_SEARCH, null, Match.PREFIX, "/search/code"),
            family(SEARCH, null, Match.PREFIX, "/search"),
            family(GRAPHQL, null, Match.PREFIX, "/graphql"),
            family(GRAPHQL, null, Match.PREFIX, "/api/graphql"),
            family(RATE_LIMIT, null, Match.PREFIX, "/rate_limit"),
            family(CODE_SCANNING_UPLOAD, HttpMethod.POST, Match.EXACT, "/repos/*/*/code-scanning/sarifs"),
            family(CODE_SCANNING_AUTOFIX, null, Match.EXACT, "/repos/*/*/code-scanning/alerts/*/autofix"),
            family(DEPENDENCY_SNAPSHOTS, HttpMethod.POST, Match.EXACT, "/repos/*/*/dependency-graph/snapshots"),
            family(DEPENDENCY_SBOM, null, Match.EXACT, "/repos/*/*/dependency-graph/sbom"),
            family(ACTIONS_RUNNER_REGISTRATION, null, Match.SUFFIX, "/actions/runners/registration-token"),
            family(ACTIONS_RUNNER_REGISTRATION, null, Match.SUFFIX, "/actions/runners/remove-token"),
            family(SCIM, null, Match.PREFIX, "/scim/v2"),
            family(INTEGRATION_MANIFEST, HttpMethod.POST, Match.EXACT, "/app-manifests/*/conversions"),
            family(SOURCE_IMPORT, null, Match.PREFIX, "/repos/*/*/import"),
            family(AUDIT_LOG, null, Match.PREFIX, "/orgs/*/audit-log"),
            family(AUDIT_LOG, null, Match.PREFIX, "/enterprises/*/audit-log"));

    private static final String GHES_PREFIX = "/api/v3";
    private static final String WILDCARD = "*";
    private static final int FIRST_SUCCESS = 200;
    private static final int FIRST_ERROR = 400;

    @Override
    public Optional<RateLimitSignal> detect(final HttpResponse response, final Clock clock) {
        return GitHubRateLimitInfo.from(response, clock).map(rateLimit -> toSignal(rateLimit, null));
    }

    @Override
    public Optional<RateLimitSignal> detect(final HttpResponse response, final HttpRequest request, final Clock clock) {
        requireNonNull(request, REQUEST_PARAMETER);
        return GitHubRateLimitInfo.from(response, clock).map(rateLimit -> toSignal(rateLimit, request));
    }

    @Override
    public Optional<RateLimitSignal> detectExhaustion(
            final HttpResponse response, final HttpRequest request, final Clock clock) {
        requireNonNull(request, REQUEST_PARAMETER);
        requireNonNull(clock, "clock");
        final int status = response.statusCode();
        if (status < FIRST_SUCCESS || status >= FIRST_ERROR || !GitHubRateLimitClassifiers.isRemainingZero(response)) {
            return Optional.empty();
        }
        final Instant serverNow = response.serverDate().orElseGet(clock::instant);
        return response.header(GitHubHeaders.RATE_LIMIT_RESET)
                .map(GitHubRateLimitClassifiers::epochSecond)
                .filter(resetAt -> resetAt.isAfter(serverNow))
                .map(resetAt -> new RateLimitSignal(null, resetAt, primaryBucket(response, request)));
    }

    @Override
    public String bucketOf(final HttpRequest request) {
        requireNonNull(request, REQUEST_PARAMETER);
        return pathBucket(request);
    }

    private static RateLimitSignal toSignal(final GitHubRateLimitInfo rateLimit, final @Nullable HttpRequest request) {
        if (rateLimit.kind() == GitHubRateLimitInfo.Kind.PRIMARY) {
            return new RateLimitSignal(
                    rateLimit.retryAfter(), rateLimit.resetAt(), resourceBucket(rateLimit.resource(), request));
        }
        final boolean isExhausted = Integer.valueOf(0).equals(rateLimit.remaining());
        return new RateLimitSignal(
                rateLimit.retryAfter(),
                rateLimit.retryAfter() == null && isExhausted ? rateLimit.resetAt() : null,
                null);
    }

    private static String primaryBucket(final HttpResponse response, final HttpRequest request) {
        return response.header(GitHubHeaders.RATE_LIMIT_RESOURCE)
                .map(String::trim)
                .filter(BUCKETS::contains)
                .orElseGet(() -> pathBucket(request));
    }

    private static @Nullable String resourceBucket(
            final @Nullable String resource, final @Nullable HttpRequest request) {
        if (resource != null && BUCKETS.contains(resource)) {
            return resource;
        }
        return request == null ? null : pathBucket(request);
    }

    private static String pathBucket(final HttpRequest request) {
        final List<String> segments = segmentsOf(withoutGhesPrefix(request.encodedPath()));
        for (final Family family : FAMILIES) {
            if (family.isMatching(segments, request.method())) {
                return family.bucket();
            }
        }
        return CORE;
    }

    // The segments below the leading slash, so that a suffix pattern does not start with an empty segment.
    private static List<String> segmentsOf(final String path) {
        final String rooted = path.startsWith("/") ? path.substring(1) : path;
        return List.of(rooted.split("/"));
    }

    private static String withoutGhesPrefix(final String path) {
        return isPath(path, GHES_PREFIX) ? path.substring(GHES_PREFIX.length()) : path;
    }

    // Whether path is root or lies below it: a segment boundary, so /search/codeql is not /search/code.
    private static boolean isPath(final String path, final String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static Family family(
            final String bucket, final @Nullable HttpMethod method, final Match match, final String pattern) {
        return new Family(bucket, method, match, segmentsOf(pattern));
    }

    /**
     * A documented resource family: the requests whose method (any when {@code null}) and path segments match
     * {@code pattern}, where a {@value #WILDCARD} segment matches any one segment.
     */
    private record Family(String bucket, @Nullable HttpMethod method, Match match, List<String> pattern) {

        boolean isMatching(final List<String> segments, final HttpMethod requestMethod) {
            if ((method != null && method != requestMethod) || segments.size() < pattern.size()) {
                return false;
            }
            return switch (match) {
                case EXACT -> segments.size() == pattern.size() && isAlignedAt(segments, 0);
                case PREFIX -> isAlignedAt(segments, 0);
                case SUFFIX -> isAlignedAt(segments, segments.size() - pattern.size());
            };
        }

        private boolean isAlignedAt(final List<String> segments, final int start) {
            for (int index = 0; index < pattern.size(); index++) {
                final String expected = pattern.get(index);
                if (!WILDCARD.equals(expected) && !expected.equals(segments.get(start + index))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** How a {@link Family} pattern lines up with the path: every segment, the leading ones, or the trailing ones. */
    private enum Match {
        EXACT,
        PREFIX,
        SUFFIX
    }
}
