package io.github.piotr_janczukowicz.github.client.ratelimit;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.transport.RateLimitSignal;
import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.DateTimeUtils;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubRateLimitDetectorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static final GitHubRateLimitDetector DETECTOR = GitHubRateLimitDetector.INSTANCE;

    @Test
    void detect_shouldBindPrimaryLimitToRequestBucket_whenResourceHeaderIsMissing() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of("120")),
                null);
        final HttpRequest request = request(HttpMethod.GET, "/user");

        assertThat(DETECTOR.detect(limited, request, FIXED_CLOCK))
                .contains(new RateLimitSignal(null, Instant.ofEpochSecond(120), "core"));
    }

    @Test
    void detect_shouldSignalResetAtWithoutBucket_whenPrimaryLimitHasNoRequestAndNoKnownResource() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of("120")),
                null);

        assertThat(DETECTOR.detect(limited, FIXED_CLOCK))
                .contains(new RateLimitSignal(null, Instant.ofEpochSecond(120), null));
    }

    @Test
    void detect_shouldBindPrimaryLimitToItsResource_whenResourceHeaderPresent() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(
                        RATE_LIMIT_REMAINING,
                        List.of("0"),
                        RATE_LIMIT_RESET,
                        List.of("120"),
                        RATE_LIMIT_RESOURCE,
                        List.of("search"),
                        RETRY_AFTER,
                        List.of("10")),
                null);

        assertThat(DETECTOR.detect(limited, FIXED_CLOCK))
                .contains(new RateLimitSignal(Duration.ofSeconds(10), Instant.ofEpochSecond(120), "search"));
    }

    @ParameterizedTest
    @CsvSource({
        "' search ',search",
        "search,search",
        "some_new_resource,code_scanning_upload",
    })
    void detect_shouldBindToTheResourceOfTheSarifUpload_whenPrimaryLimitHasResourceHeader(
            final String resource, final String bucket) {
        final HttpResponse limited = primaryWithResource(resource);
        final HttpRequest request = request(HttpMethod.POST, "/repos/o/r/code-scanning/sarifs");

        assertThat(DETECTOR.detect(limited, request, FIXED_CLOCK))
                .contains(new RateLimitSignal(null, Instant.ofEpochSecond(120), bucket));
    }

    @Test
    void detect_shouldSignalOnlyRetryAfterForAllBuckets_whenSecondaryLimit() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(
                        RETRY_AFTER,
                        List.of("30"),
                        RATE_LIMIT_REMAINING,
                        List.of("4000"),
                        RATE_LIMIT_RESET,
                        List.of("120")),
                null);

        assertThat(DETECTOR.detect(limited, request(HttpMethod.GET, "/user"), FIXED_CLOCK))
                .contains(new RateLimitSignal(Duration.ofSeconds(30), null, null));
    }

    @Test
    void detect_shouldSignalResetForAllBuckets_whenSecondaryLimitHasZeroRemainingAndNoRetryAfter() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of("120")),
                HttpBody.of("{\"message\":\"secondary rate limit\"}".getBytes(StandardCharsets.UTF_8)));

        assertThat(DETECTOR.detect(limited, FIXED_CLOCK))
                .contains(new RateLimitSignal(null, Instant.ofEpochSecond(120), null));
    }

    @Test
    void detect_shouldPreferRetryAfterOverReset_whenSecondaryLimitHasBoth() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(
                        RETRY_AFTER,
                        List.of("30"),
                        RATE_LIMIT_REMAINING,
                        List.of("0"),
                        RATE_LIMIT_RESET,
                        List.of("120")),
                HttpBody.of("{\"message\":\"secondary rate limit\"}".getBytes(StandardCharsets.UTF_8)));

        assertThat(DETECTOR.detect(limited, FIXED_CLOCK))
                .contains(new RateLimitSignal(Duration.ofSeconds(30), null, null));
    }

    @Test
    void detect_shouldBeEmpty_whenForbiddenHasOnlyARetryAfter() {
        final HttpResponse forbidden =
                HttpResponses.of(HttpStatus.FORBIDDEN.code(), Map.of(RETRY_AFTER, List.of("30")), null);

        assertThat(DETECTOR.detect(forbidden, FIXED_CLOCK)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "GET,/search/code,code_search",
        "GET,/search/code/,code_search",
        "GET,/search/issues,search",
        "GET,/search,search",
        "GET,/search/commits,search",
        "GET,/api/v3/search/commits,search",
        "GET,/api/v3/search/code,code_search",
        "GET,/search/codeql,search",
        "GET,/graphql,graphql",
        "GET,/api/graphql,graphql",
        "GET,/user,core",
        "GET,/repos/o/r,core",
        "GET,/api/v3/user,core",
        "GET,/api/v3,core",
        "GET,/rate_limit,rate_limit",
        "GET,/api/v3/rate_limit,rate_limit",
        "GET,/app/installations/1/access_tokens,core",
        "POST,/repos/o/r/code-scanning/sarifs,code_scanning_upload",
        "POST,/api/v3/repos/o/r/code-scanning/sarifs,code_scanning_upload",
        "GET,/repos/o/r/code-scanning/sarifs/abc,core",
        "GET,/repos/o/r/code-scanning/alerts/7/autofix,code_scanning_autofix",
        "GET,/api/v3/repos/o/r/code-scanning/alerts/7/autofix,code_scanning_autofix",
        "POST,/repos/o/r/dependency-graph/snapshots,dependency_snapshots",
        "POST,/api/v3/repos/o/r/dependency-graph/snapshots,dependency_snapshots",
        "GET,/repos/o/r/dependency-graph/snapshots,core",
        "GET,/repos/o/r/dependency-graph/sbom,dependency_sbom",
        "GET,/api/v3/repos/o/r/dependency-graph/sbom,dependency_sbom",
        "POST,/repos/o/r/actions/runners/registration-token,actions_runner_registration",
        "POST,/repos/o/r/actions/runners/remove-token,actions_runner_registration",
        "POST,/orgs/o/actions/runners/registration-token,actions_runner_registration",
        "POST,/enterprises/e/actions/runners/remove-token,actions_runner_registration",
        "POST,/api/v3/repos/o/r/actions/runners/registration-token,actions_runner_registration",
        "GET,/scim/v2/Users,scim",
        "GET,/api/v3/scim/v2/Users,scim",
        "GET,/scim,core",
        "POST,/app-manifests/abc/conversions,integration_manifest",
        "GET,/app-manifests/abc/conversions,core",
        "GET,/repos/o/r/import,source_import",
        "PATCH,/repos/o/r/import/lfs,source_import",
        "GET,/repos/o/r/imports,core",
        "GET,/orgs/o/audit-log,audit_log",
        "GET,/enterprises/e/audit-log,audit_log",
        "GET,/api/v3/orgs/o/audit-log,audit_log",
        "GET,/orgs/o/audit-logs,core",
        "POST,/api/v3/app-manifests/c/conversions,integration_manifest",
        "GET,/api/v3/repos/o/r/import,source_import",
        "POST,/api/v3/orgs/o/actions/runners/remove-token,actions_runner_registration",
        "GET,/api/v3/enterprises/e/audit-log,audit_log"
    })
    void bucketOf_shouldMapPathToTheResourceGitHubCounts(final String method, final String path, final String bucket) {
        final HttpRequest request = new HttpRequest(HttpMethod.valueOf(method), uri(path), HttpHeaders.empty(), null);

        assertThat(DETECTOR.bucketOf(request)).isEqualTo(bucket);
    }

    @ParameterizedTest
    @MethodSource("bucketsOnEveryHost")
    void bucketOf_shouldMapPathToTheSameBucket_onEveryHost(final URI base, final String relative, final String bucket) {
        final HttpRequest request = new HttpRequest(HttpMethod.GET, base.resolve(relative), HttpHeaders.empty(), null);

        assertThat(DETECTOR.bucketOf(request)).isEqualTo(bucket);
    }

    @Test
    void detect_shouldBeEmpty_whenNotRateLimited() {
        final HttpResponse forbidden = HttpResponses.of(HttpStatus.FORBIDDEN.code(), Map.of(), null);

        assertThat(DETECTOR.detect(forbidden, FIXED_CLOCK)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775807", "-9223372036854775808", "99999999999999999"})
    void detect_shouldNotThrow_whenResetHeaderOutOfInstantRange(final String reset) {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of(reset)),
                null);

        final Throwable thrown = catchThrowable(() -> DETECTOR.detect(response, FIXED_CLOCK));

        assertThat(thrown).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 304})
    void detectExhaustion_shouldSignalReset_whenSuccessOrNotModifiedSpentTheQuota(final int status) {
        final HttpResponse response = HttpResponses.of(
                status, Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of("120")), null);
        final HttpRequest request = request(HttpMethod.GET, "/user");

        assertThat(DETECTOR.detectExhaustion(response, request, FIXED_CLOCK))
                .contains(new RateLimitSignal(null, Instant.ofEpochSecond(120), "core"));
    }

    @Test
    void detectExhaustion_shouldBindToHeaderResource_whenKnownResourceIsNamed() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.OK.code(),
                Map.of(
                        RATE_LIMIT_REMAINING,
                        List.of("0"),
                        RATE_LIMIT_RESET,
                        List.of("120"),
                        RATE_LIMIT_RESOURCE,
                        List.of("graphql")),
                null);
        final HttpRequest request = request(HttpMethod.GET, "/user");

        assertThat(DETECTOR.detectExhaustion(response, request, FIXED_CLOCK))
                .contains(new RateLimitSignal(null, Instant.ofEpochSecond(120), "graphql"));
    }

    @Test
    void detectExhaustion_shouldBeEmpty_whenRemainingIsMissing() {
        final HttpResponse response =
                HttpResponses.of(HttpStatus.OK.code(), Map.of(RATE_LIMIT_RESET, List.of("120")), null);

        assertThat(DETECTOR.detectExhaustion(response, request(HttpMethod.GET, "/user"), FIXED_CLOCK))
                .isEmpty();
    }

    @Test
    void detectExhaustion_shouldBeEmpty_whenRemainingIsPositive() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.OK.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("4999"), RATE_LIMIT_RESET, List.of("120")),
                null);

        assertThat(DETECTOR.detectExhaustion(response, request(HttpMethod.GET, "/user"), FIXED_CLOCK))
                .isEmpty();
    }

    @Test
    void detectExhaustion_shouldBeEmpty_whenResetIsNotInTheServerFuture() {
        final HttpResponse response = HttpResponses.of(
                HttpStatus.OK.code(),
                Map.of(
                        DATE,
                        List.of(DateTimeUtils.toRfc1123(Instant.ofEpochSecond(180))),
                        RATE_LIMIT_REMAINING,
                        List.of("0"),
                        RATE_LIMIT_RESET,
                        List.of("120")),
                null);

        assertThat(DETECTOR.detectExhaustion(response, request(HttpMethod.GET, "/user"), FIXED_CLOCK))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 400, 404, 500})
    void detectExhaustion_shouldBeEmpty_whenResponseIsNotSuccessOrRedirect(final int status) {
        final HttpResponse response = HttpResponses.of(
                status, Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of("120")), null);

        assertThat(DETECTOR.detectExhaustion(response, request(HttpMethod.GET, "/user"), FIXED_CLOCK))
                .isEmpty();
    }

    private static HttpResponse primaryWithResource(final String resource) {
        return HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(
                        RATE_LIMIT_REMAINING,
                        List.of("0"),
                        RATE_LIMIT_RESET,
                        List.of("120"),
                        RATE_LIMIT_RESOURCE,
                        List.of(resource)),
                null);
    }

    private static Stream<Arguments> bucketsOnEveryHost() {
        return TestHosts.apiBases()
                .flatMap(base -> Stream.of(
                        Arguments.of(base, "search/code", "code_search"),
                        Arguments.of(base, "search/issues", "search"),
                        Arguments.of(base, "user", "core"),
                        Arguments.of(base, "rate_limit", "rate_limit"),
                        Arguments.of(base, "repos/o/r/dependency-graph/sbom", "dependency_sbom")));
    }

    private static HttpRequest request(final HttpMethod method, final String path) {
        return new HttpRequest(method, uri(path), HttpHeaders.empty(), null);
    }

    private static URI uri(final String path) {
        return URI.create("https://api.github.com" + path);
    }

    @Test
    void detect_shouldSignalResetForAllBuckets_whenAbuseWordingIsInATextBody() {
        final HttpResponse limited = HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(RATE_LIMIT_REMAINING, List.of("0"), RATE_LIMIT_RESET, List.of("120")),
                HttpBody.of("You have triggered an abuse detection mechanism.".getBytes(StandardCharsets.UTF_8)));

        assertThat(DETECTOR.detect(limited, FIXED_CLOCK))
                .contains(new RateLimitSignal(null, Instant.ofEpochSecond(120), null));
    }
}
