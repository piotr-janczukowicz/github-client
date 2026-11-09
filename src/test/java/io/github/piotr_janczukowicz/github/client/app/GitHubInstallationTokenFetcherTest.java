package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
class GitHubInstallationTokenFetcherTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long INSTALLATION_ID = 42L;
    private static final String PATH = "/app/installations/42/access_tokens";
    private static final Duration MARGIN = Duration.ofMinutes(5);

    private HttpTransport transport;

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void fetch_shouldPostWithoutBody_whenScopeIsAll() throws Exception {
        when(transport.execute(any())).thenReturn(issued("ghs_all", NOW.plus(Duration.ofHours(1))));
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, MARGIN, NOW);

        fetcher.fetch(INSTALLATION_ID);

        verify(transport).execute(captor.capture());
        final HttpRequest sent = captor.getValue();
        assertSoftly(softly -> {
            softly.assertThat(sent.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(sent.encodedPath()).isEqualTo(PATH);
            softly.assertThat(sent.body().isAbsent()).isTrue();
            softly.assertThat(sent.headers().get(CONTENT_TYPE)).isEmpty();
        });
    }

    @Test
    void fetch_shouldPostScopeAsJsonBody_whenScopeIsNarrowed() throws Exception {
        when(transport.execute(any())).thenReturn(issued("ghs_narrow", NOW.plus(Duration.ofHours(1))));
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        final GitHubInstallationScope scope = GitHubInstallationScope.ALL.withRepositories(List.of("repo-a"));
        final GitHubInstallationTokenFetcher fetcher = fetcher(TestClients.client(transport), scope, MARGIN, NOW);

        fetcher.fetch(INSTALLATION_ID);

        verify(transport).execute(captor.capture());
        final HttpRequest sent = captor.getValue();
        assertSoftly(softly -> {
            softly.assertThat(sent.method()).isEqualTo(HttpMethod.POST);
            softly.assertThat(sent.encodedPath()).isEqualTo(PATH);
            softly.assertThat(sent.headers().get(CONTENT_TYPE)).contains("application/json");
            softly.assertThat(sent.mapBody(bytes -> new String(bytes, StandardCharsets.UTF_8), () -> ""))
                    .isEqualTo("{\"repositories\":[\"repo-a\"]}");
        });
    }

    @Test
    void fetch_shouldThrowIllegalState_whenTokenIsBlank() throws Exception {
        when(transport.execute(any())).thenReturn(issued(" ", NOW.plus(Duration.ofHours(1))));
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, MARGIN, NOW);

        final Throwable thrown = catchThrowable(() -> fetcher.fetch(INSTALLATION_ID));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub returned an installation token response without token or expiry");
    }

    @Test
    void fetch_shouldThrowIllegalState_whenExpiryIsMissing() throws Exception {
        when(transport.execute(any())).thenReturn(json("{\"token\":\"ghs_no_expiry\"}"));
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, MARGIN, NOW);

        final Throwable thrown = catchThrowable(() -> fetcher.fetch(INSTALLATION_ID));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub returned an installation token response without token or expiry");
    }

    @Test
    void fetch_shouldCapRefreshMarginAtHalfTheLifetime_whenMarginIsLonger() throws Exception {
        when(transport.execute(any())).thenReturn(issued("ghs_short", NOW.plus(Duration.ofMinutes(10))));
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, Duration.ofMinutes(30), NOW);

        final GitHubInstallationTokenCache.CachedToken token = fetcher.fetch(INSTALLATION_ID);

        assertThat(token.refreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void fetch_shouldRefreshMarginBeforeExpiry_whenMarginIsShorterThanHalfTheLifetime() throws Exception {
        when(transport.execute(any())).thenReturn(issued("ghs_long", NOW.plus(Duration.ofMinutes(60))));
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, MARGIN, NOW);

        final GitHubInstallationTokenCache.CachedToken token = fetcher.fetch(INSTALLATION_ID);

        assertThat(token.refreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(55)));
    }

    @Test
    void fetch_shouldMeasureLifetimeOnServerClock_whenLocalClockIsAhead() throws Exception {
        final Instant serverNow = NOW.minus(Duration.ofHours(2));
        when(transport.execute(any()))
                .thenReturn(issuedAt("ghs_ahead", serverNow.plus(Duration.ofHours(1)), serverNow));
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, MARGIN, NOW);

        final GitHubInstallationTokenCache.CachedToken token = fetcher.fetch(INSTALLATION_ID);

        assertSoftly(softly -> {
            softly.assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
            softly.assertThat(token.refreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(55)));
        });
    }

    @Test
    void fetch_shouldMeasureLifetimeOnServerClock_whenLocalClockIsBehind() throws Exception {
        final Instant serverNow = NOW.plus(Duration.ofHours(2));
        when(transport.execute(any()))
                .thenReturn(issuedAt("ghs_behind", serverNow.plus(Duration.ofHours(1)), serverNow));
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, MARGIN, NOW);

        final GitHubInstallationTokenCache.CachedToken token = fetcher.fetch(INSTALLATION_ID);

        assertSoftly(softly -> {
            softly.assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
            softly.assertThat(token.refreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(55)));
        });
    }

    @Test
    void fetch_shouldRefreshAtExpiry_whenTokenIsAlreadyExpired() throws Exception {
        final Instant expiresAt = NOW.minus(Duration.ofMinutes(1));
        when(transport.execute(any())).thenReturn(issued("ghs_expired", expiresAt));
        final GitHubInstallationTokenFetcher fetcher =
                fetcher(TestClients.client(transport), GitHubInstallationScope.ALL, MARGIN, NOW);

        final GitHubInstallationTokenCache.CachedToken token = fetcher.fetch(INSTALLATION_ID);

        assertThat(token.refreshAt()).isEqualTo(expiresAt);
    }

    @Test
    void toString_shouldHideToken_whenInstallationTokenIsPrinted() {
        final GitHubInstallationTokenFetcher.InstallationToken token =
                new GitHubInstallationTokenFetcher.InstallationToken("ghs_secret", NOW);

        final String printed = token.toString();

        assertThat(printed)
                .doesNotContain("ghs_secret")
                .isEqualTo("InstallationToken[token=<redacted>, expiresAt=" + NOW + "]");
    }

    private static GitHubInstallationTokenFetcher fetcher(
            final GitHubClient client, final GitHubInstallationScope scope, final Duration margin, final Instant now) {
        return new GitHubInstallationTokenFetcher(
                margin, Clock.fixed(now, ZoneOffset.UTC), TestClients.caller(client), scope.toRequestBody());
    }

    private static HttpResponse issued(final String token, final Instant expiresAt) {
        return json("{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}");
    }

    private static HttpResponse issuedAt(final String token, final Instant expiresAt, final Instant serverNow) {
        return new HttpResponse(
                201,
                HttpHeaders.of(
                        CONTENT_TYPE,
                        "application/json",
                        DATE,
                        DateTimeFormatter.RFC_1123_DATE_TIME.format(serverNow.atOffset(ZoneOffset.UTC))),
                HttpBody.of(("{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse json(final String body) {
        return new HttpResponse(
                201,
                HttpHeaders.of(CONTENT_TYPE, "application/json"),
                HttpBody.of(body.getBytes(StandardCharsets.UTF_8)));
    }
}
