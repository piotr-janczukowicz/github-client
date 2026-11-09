package io.github.piotr_janczukowicz.github.client.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

@UnitTest
class GitHubInstallationTokenCacheTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long INSTALLATION_ID = 42L;
    private static final Duration INITIAL_BACKOFF = Duration.ofMinutes(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final Duration MIN_RENEW_INTERVAL = Duration.ofMinutes(5);

    @Mock
    private GitHubInstallationTokenFetcher fetcher;

    @Test
    void token_shouldFetchOnce_whenCacheIsEmpty() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_first");
        verify(fetcher, times(1)).fetch(INSTALLATION_ID);
    }

    @Test
    void token_shouldReuseCachedToken_whenBeforeRefreshAt() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        clock.advance(Duration.ofMinutes(49));

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_first");
        verify(fetcher, times(1)).fetch(INSTALLATION_ID);
    }

    @Test
    void token_shouldRenewToken_whenAfterRefreshAtAndBeforeExpiry() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID))
                .thenReturn(cachedToken("ghs_first", NOW), cachedToken("ghs_second", NOW.plus(Duration.ofMinutes(51))));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        clock.advance(Duration.ofMinutes(51));

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_second");
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void token_shouldReturnUnexpiredToken_whenRenewalFailsBeforeExpiry() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID))
                .thenReturn(cachedToken("ghs_first", NOW))
                .thenThrow(new IllegalStateException("token request failed"));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        clock.advance(Duration.ofMinutes(51));

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_first");
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void token_shouldNotFetchAgain_whenBackoffIsActiveAndTokenIsUnexpired() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID))
                .thenReturn(cachedToken("ghs_first", NOW))
                .thenThrow(new IllegalStateException("token request failed"));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        clock.advance(Duration.ofMinutes(51));
        cache.token();

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_first");
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void token_shouldThrowUnavailable_whenBackoffOutlivesExpiredToken() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID))
                .thenReturn(cachedToken("ghs_first", NOW))
                .thenThrow(new IllegalStateException("token request failed"));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        clock.advance(Duration.ofMinutes(51));
        cache.token();
        clock.advance(Duration.ofMinutes(10));

        final Throwable thrown = catchThrowable(cache::token);

        assertThat(thrown)
                .isInstanceOf(GitHubInstallationTokenUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void token_shouldFetchAgain_whenBackoffHasPassed() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID))
                .thenThrow(new IllegalStateException("token request failed"))
                .thenReturn(cachedToken("ghs_second", NOW.plus(Duration.ofMinutes(31))));
        final GitHubInstallationTokenCache cache = cache(clock);
        catchThrowable(cache::token);
        clock.advance(Duration.ofMinutes(31));

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_second");
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void token_shouldRethrowFetchFailure_whenNoTokenIsCached() {
        final MutableClock clock = new MutableClock(NOW);
        final IllegalStateException failure = new IllegalStateException("token request failed");
        when(fetcher.fetch(INSTALLATION_ID)).thenThrow(failure);
        final GitHubInstallationTokenCache cache = cache(clock);

        final Throwable thrown = catchThrowable(cache::token);

        assertThat(thrown).isSameAs(failure);
    }

    @Test
    void invalidate_shouldDropToken_whenCalledWithoutArgument() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW), cachedToken("ghs_second", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        cache.invalidate();

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_second");
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void invalidate_shouldDropToken_whenStaleTokenMatchesCachedToken() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW), cachedToken("ghs_second", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        cache.invalidate("ghs_first");

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_second");
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void invalidate_shouldKeepToken_whenStaleTokenDiffersFromCachedToken() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        cache.invalidate("ghs_other");

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_first");
        verify(fetcher, times(1)).fetch(INSTALLATION_ID);
    }

    @Test
    void invalidate_shouldMakeNextTokenFetchANewOne() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW), cachedToken("ghs_second", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        final String first = cache.token();
        cache.invalidate();
        final String second = cache.token();

        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo("ghs_first");
            softly.assertThat(second).isEqualTo("ghs_second");
        });
        verify(fetcher, times(2)).fetch(INSTALLATION_ID);
    }

    @Test
    void invalidate_shouldRejectNull_whenStaleTokenIsNull() {
        final MutableClock clock = new MutableClock(NOW);
        final GitHubInstallationTokenCache cache = cache(clock);

        final Throwable thrown = catchThrowable(() -> cache.invalidate(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
    }

    @Test
    void isRenewableAfterRejection_shouldReturnTrue_whenTokenIsNotCached() {
        final MutableClock clock = new MutableClock(NOW);
        final GitHubInstallationTokenCache cache = cache(clock);

        final boolean renewable = cache.isRenewableAfterRejection("ghs_first");

        assertThat(renewable).isTrue();
    }

    @Test
    void isRenewableAfterRejection_shouldReturnTrue_whenRejectedTokenIsNotTheCachedOne() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();

        final boolean renewable = cache.isRenewableAfterRejection("ghs_other");

        assertThat(renewable).isTrue();
    }

    @Test
    void isRenewableAfterRejection_shouldReturnFalse_whenRejectedWithinMinRenewInterval() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        clock.advance(Duration.ofMinutes(1));

        final boolean renewable = cache.isRenewableAfterRejection("ghs_first");

        assertThat(renewable).isFalse();
    }

    @Test
    void isRenewableAfterRejection_shouldReturnTrue_whenRejectedAfterMinRenewInterval() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.token();
        clock.advance(Duration.ofMinutes(6));

        final boolean renewable = cache.isRenewableAfterRejection("ghs_first");

        assertThat(renewable).isTrue();
    }

    @Test
    void toString_shouldHideTokenValue_whenCalled() {
        final GitHubInstallationTokenCache.CachedToken token = cachedToken("ghs_secret_value", NOW);

        final String text = token.toString();

        assertThat(text).doesNotContain("ghs_secret_value").startsWith("CachedToken[");
    }

    private GitHubInstallationTokenCache cache(final MutableClock clock) {
        return new GitHubInstallationTokenCache(
                INSTALLATION_ID, INITIAL_BACKOFF, MAX_BACKOFF, MIN_RENEW_INTERVAL, clock, fetcher);
    }

    private static GitHubInstallationTokenCache.CachedToken cachedToken(final String value, final Instant fetchedAt) {
        return new GitHubInstallationTokenCache.CachedToken(
                value, fetchedAt, fetchedAt.plus(Duration.ofHours(1)), fetchedAt.plus(Duration.ofMinutes(50)));
    }

    @Test
    void invalidate_shouldChangeNothing_whenNothingIsCached() {
        final MutableClock clock = new MutableClock(NOW);
        when(fetcher.fetch(INSTALLATION_ID)).thenReturn(cachedToken("ghs_first", NOW));
        final GitHubInstallationTokenCache cache = cache(clock);
        cache.invalidate("ghs_any");

        final String token = cache.token();

        assertThat(token).isEqualTo("ghs_first");
    }
}
