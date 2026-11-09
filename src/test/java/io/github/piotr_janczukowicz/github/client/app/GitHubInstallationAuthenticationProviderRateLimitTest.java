package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.SwitchableAuthenticationProvider;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.DateTimeUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;

@UnitTest
@Isolated
class GitHubInstallationAuthenticationProviderRateLimitTest {

    private static final String READ_PATH = "/repos/o/r";
    private static final String LIMITED_TOKEN_42 = "ghs_42_1";
    private static final String INSTALLATION_TOKEN_42 = "ghs_42_1";

    private HttpTransport transport;

    private final Set<String> limitedTokens = ConcurrentHashMap.newKeySet();
    private final List<String> minted = new CopyOnWriteArrayList<>();
    private final List<String> reads = new CopyOnWriteArrayList<>();
    private final Map<Long, AtomicInteger> mints = new ConcurrentHashMap<>();

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void execute_shouldFailFastWithoutSending_whenTokenRotatesDuringBlock() throws Exception {
        limitedTokens.add(LIMITED_TOKEN_42);
        when(transport.execute(any())).thenAnswer(invocation -> respond(invocation.getArgument(0)));

        try (GitHubInstallationAuthenticationProvider auth =
                        TestInstallationAuths.create(transport, configuration(42));
                GitHubClient client = TestGitHubClients.defaultStack(transport, auth)) {
            final Throwable limited = catchThrowable(() -> client.execute(HttpCall.get(READ_PATH)));
            final boolean dropped = auth.isRenewableAfterRejection(Authentication.ofBearer(LIMITED_TOKEN_42));
            final Throwable blocked = catchThrowable(() -> client.execute(HttpCall.get(READ_PATH)));

            assertSoftly(softly -> {
                softly.assertThat(limited).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) limited).isClientSideBlock())
                        .isFalse();
                softly.assertThat(dropped).isTrue();
                softly.assertThat(blocked).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(((GitHubRateLimitException) blocked).isClientSideBlock())
                        .isTrue();
                softly.assertThat(minted).containsExactly(LIMITED_TOKEN_42, "ghs_42_2");
                softly.assertThat(reads).containsExactly("Bearer " + LIMITED_TOKEN_42);
            });
        }
    }

    @Test
    void execute_shouldNotBlockOtherInstallation_whenInstallationIsRateLimited() throws Exception {
        limitedTokens.add(LIMITED_TOKEN_42);
        when(transport.execute(any())).thenAnswer(invocation -> respond(invocation.getArgument(0)));
        final SwitchableAuthenticationProvider credential = new SwitchableAuthenticationProvider(TestClients.auth());

        try (GitHubClient client = TestGitHubClients.defaultStack(transport, credential);
                GitHubInstallationAuthenticationProvider first =
                        TestInstallationAuths.create(transport, configuration(42));
                GitHubInstallationAuthenticationProvider second =
                        TestInstallationAuths.create(transport, configuration(43))) {
            credential.use(first);
            final Throwable limited = catchThrowable(() -> client.execute(HttpCall.get(READ_PATH)));
            credential.use(second);
            final HttpResponse other = client.execute(HttpCall.get(READ_PATH));

            assertSoftly(softly -> {
                softly.assertThat(limited).isInstanceOf(GitHubRateLimitException.class);
                softly.assertThat(other.statusCode()).isEqualTo(HttpStatus.OK.code());
                softly.assertThat(reads).containsExactly("Bearer " + LIMITED_TOKEN_42, "Bearer ghs_43_1");
            });
        }
    }

    @Test
    void authentication_shouldNameAppAndInstallation_whenInstallationAuth() throws Exception {
        when(transport.execute(any())).thenAnswer(invocation -> respond(invocation.getArgument(0)));

        try (GitHubInstallationAuthenticationProvider auth =
                TestInstallationAuths.create(transport, configuration(42))) {
            assertThat(auth.authentication().map(Authentication::identity)).contains("app:Iv1.test/installation:42");
        }
    }

    @Test
    void execute_shouldSendInstallationIdentityAsCredentialId_whenInstallationAuth() throws Exception {
        when(transport.execute(any())).thenAnswer(invocation -> respond(invocation.getArgument(0)));
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);

        try (GitHubInstallationAuthenticationProvider auth =
                        TestInstallationAuths.create(transport, configuration(42));
                GitHubClient client = TestGitHubClients.defaultStack(transport, auth)) {
            client.execute(HttpCall.get(READ_PATH));
        }
        verify(transport, atLeastOnce()).execute(sent.capture());

        assertSoftly(softly -> {
            softly.assertThat(sent.getValue().credentialId()).isEqualTo("app:Iv1.test/installation:42");
            softly.assertThat(sent.getValue().headers().get(AUTHORIZATION)).contains("Bearer " + INSTALLATION_TOKEN_42);
        });
    }

    private static GitHubInstallationAuthConfiguration configuration(final long installationId) {
        return GitHubInstallationAuthConfiguration.builder()
                .appId("Iv1.test")
                .privateKeyPem(AppKeys.pkcs1Pem())
                .installationId(installationId)
                .minRenewInterval(Duration.ZERO)
                .build();
    }

    private HttpResponse respond(final HttpRequest request) {
        final String path = request.encodedPath();
        if (path.endsWith("/access_tokens")) {
            return mint(path);
        }
        final String authorization = request.headers().get(AUTHORIZATION).orElse("");
        reads.add(authorization);
        if (limitedTokens.contains(authorization.substring("Bearer ".length()))) {
            return rateLimited();
        }
        return new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent());
    }

    private HttpResponse mint(final String path) {
        final long installationId = Long.parseLong(path.split("/")[3]);
        final int generation =
                mints.computeIfAbsent(installationId, id -> new AtomicInteger()).incrementAndGet();
        final String token = "ghs_" + installationId + "_" + generation;
        minted.add(token);
        final String body = "{\"token\":\"" + token + "\",\"expires_at\":\"2099-01-01T00:00:00Z\"}";
        return HttpResponses.of(
                HttpStatus.CREATED.code(),
                Map.of(CONTENT_TYPE, List.of("application/json")),
                HttpBody.of(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse rateLimited() {
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return HttpResponses.of(
                HttpStatus.FORBIDDEN.code(),
                Map.of(
                        DATE, List.of(DateTimeUtils.toRfc1123(now)),
                        RATE_LIMIT_LIMIT, List.of("5000"),
                        RATE_LIMIT_REMAINING, List.of("0"),
                        RATE_LIMIT_USED, List.of("5000"),
                        RATE_LIMIT_RESET,
                                List.of(Long.toString(now.plusSeconds(600).getEpochSecond())),
                        RATE_LIMIT_RESOURCE, List.of("core")),
                HttpBody.of("{\"message\":\"API rate limit exceeded.\"}".getBytes(StandardCharsets.UTF_8)));
    }
}
