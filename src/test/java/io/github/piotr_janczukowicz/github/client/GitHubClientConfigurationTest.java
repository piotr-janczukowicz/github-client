package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.http.client.HttpCallObserver;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransportConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubClientConfigurationTest {

    @Test
    void defaults_shouldUseOneConcurrentRequestAndOneSecondMutationInterval_whenCalled() {
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.defaults();

        assertSoftly(softly -> {
            softly.assertThat(configuration.transport().maxConcurrentRequests()).isEqualTo(1);
            softly.assertThat(configuration.transport().maxIdleConnections()).isEqualTo(1);
            softly.assertThat(configuration.transport().maxSlotWait())
                    .isEqualTo(HttpTransportConfiguration.DEFAULT_MAX_SLOT_WAIT);
            softly.assertThat(configuration.mutationInterval()).isEqualTo(Duration.ofSeconds(1));
            softly.assertThat(configuration.configuration().baseUri())
                    .isEqualTo(GitHubClientConfiguration.DEFAULT_BASE_URI);
            softly.assertThat(configuration.rateLimitListener()).isNull();
            softly.assertThat(configuration.callObserver()).isNull();
            softly.assertThat(configuration.allowInsecureHttp()).isFalse();
        });
    }

    @Test
    void builder_shouldKeepPagination_whenSet() {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(2).perPage(30).build();

        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().pagination(pagination).build();

        assertThat(configuration.pagination()).isEqualTo(pagination);
    }

    @Test
    void builder_shouldRejectPagination_whenNull() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.pagination(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("pagination");
    }

    @Test
    void build_shouldRejectRetry_whenRateLimitedResponsesNotRetried() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .retry(RetryConfiguration.builder()
                        .retryRateLimitedResponses(false)
                        .build());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rate-limited");
    }

    @Test
    void build_shouldRejectRetry_whenRateLimitsNotCoordinated() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .retry(RetryConfiguration.builder().coordinateRateLimits(false).build());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coordinate");
    }

    @Test
    void build_shouldRejectRetry_whenFewerThanTwoRateLimitAttempts() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .retry(RetryConfiguration.builder().maxRateLimitAttempts(1).build());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRateLimitAttempts");
    }

    @Test
    void retry_shouldRejectDetector_whenCustomDetectorSet() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder();
        final RetryConfiguration retry =
                RetryConfiguration.defaults().withRateLimitDetector((response, clock) -> Optional.empty());

        final Throwable thrown = catchThrowable(() -> builder.retry(retry));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GitHub clients always use the GitHub rate-limit detector");
    }

    @Test
    void retry_shouldAcceptDefaults_whenDefaultRetryGiven() {
        final RetryConfiguration retry = RetryConfiguration.defaults();

        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().retry(retry).build();

        assertThat(configuration.retry()).isSameAs(retry);
    }

    @Test
    void retry_shouldAcceptCustomSettings_whenAttemptsBackoffAndJitterSet() {
        final RetryConfiguration retry = RetryConfiguration.builder()
                .maxRateLimitAttempts(5)
                .defaultBackoff(Duration.ofMillis(250))
                .maxBackoff(Duration.ofSeconds(10))
                .rateLimitJitter(Duration.ZERO)
                .fullJitter(false)
                .build();

        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().retry(retry).build();

        assertSoftly(softly -> {
            softly.assertThat(configuration.retry()).isSameAs(retry);
            softly.assertThat(configuration.retry().attempts().maxRateLimitAttempts())
                    .isEqualTo(5);
        });
    }

    @Test
    void build_shouldRejectConcurrency_whenAboveOneHundred() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .transport(HttpTransportConfiguration.builder()
                        .maxConcurrentRequests(101)
                        .build());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxConcurrentRequests");
    }

    @Test
    void build_shouldRejectConcurrency_whenUnlimited() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .transport(HttpTransportConfiguration.builder()
                        .maxConcurrentRequests(HttpTransportConfiguration.UNLIMITED_REQUESTS)
                        .build());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxConcurrentRequests");
    }

    @Test
    void flavor_shouldBeEmpty_whenNotConfigured() {
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.defaults();

        assertThat(configuration.flavor()).isEmpty();
    }

    @Test
    void flavor_shouldReturnFlavor_whenSetOnBuilder() {
        final GitHubClientConfiguration configuration =
                GitHubClientConfiguration.builder().flavor(GitHubFlavor.GHEC).build();

        assertThat(configuration.flavor()).contains(GitHubFlavor.GHEC);
    }

    @Test
    void ghesVersion_shouldBeEmpty_whenNotConfigured() {
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.defaults();

        assertThat(configuration.ghesVersion()).isEmpty();
    }

    @Test
    void build_shouldKeepFlavorAndVersion_whenGhesClientHasApiV3BaseUri() {
        final GitHubClientConfiguration configuration =
                ghesBuilder(new GhesVersion(3, 22)).build();

        assertSoftly(softly -> {
            softly.assertThat(configuration.flavor()).contains(GitHubFlavor.GHES);
            softly.assertThat(configuration.ghesVersion()).contains(new GhesVersion(3, 22));
        });
    }

    @Test
    void build_shouldAcceptEverySupportedVersion_whenFlavorIsGhes() {
        final List<GhesVersion> supported = GhesVersion.supported();

        final List<GhesVersion> built = supported.stream()
                .map(version -> ghesBuilder(version).build().ghesVersion().orElseThrow())
                .toList();

        assertThat(built).isEqualTo(supported);
    }

    @Test
    void build_shouldThrowAndListSupportedVersions_whenGhesHasNoVersion() {
        final GitHubClientConfiguration.Builder builder = ghesBuilder(null);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghesVersion is required")
                .hasMessageContaining(GhesVersion.supported().toString());
    }

    @Test
    void build_shouldThrowAndListSupportedVersions_whenGhesVersionIsNewerThanSupported() {
        final GitHubClientConfiguration.Builder builder = ghesBuilder(new GhesVersion(3, 99));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3.99 is not supported")
                .hasMessageContaining(GhesVersion.supported().toString());
    }

    @Test
    void build_shouldThrow_whenGhesVersionIsOlderThanSupported() {
        final GitHubClientConfiguration.Builder builder = ghesBuilder(new GhesVersion(3, 19));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3.19 is not supported");
    }

    @Test
    void build_shouldThrow_whenVersionIsSetWithoutGhesFlavor() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().flavor(GitHubFlavor.GHEC).ghesVersion(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs the GHES flavor");
    }

    @Test
    void build_shouldThrow_whenVersionIsSetWithoutAnyFlavor() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().ghesVersion(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs the GHES flavor");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.github.com/",
                "https://ghe.example/",
                "https://ghe.example/api/v4/",
                "https://ghe.example/prefix/api/v3/"
            })
    void build_shouldThrow_whenGhesBaseUriPathIsNotApiV3(final String baseUri) {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(URI.create(baseUri)))
                .flavor(GitHubFlavor.GHES)
                .ghesVersion(new GhesVersion(3, 22));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/api/v3/");
    }

    @Test
    void build_shouldAcceptGhesBaseUriWithoutTrailingSlash_whenPathEndsInApiV3() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(URI.create("https://ghe.example/api/v3")))
                .flavor(GitHubFlavor.GHES)
                .ghesVersion(new GhesVersion(3, 21));

        final GitHubClientConfiguration configuration = builder.build();

        assertThat(configuration.ghesVersion()).contains(new GhesVersion(3, 21));
    }

    @ParameterizedTest
    @EnumSource(
            value = GitHubFlavor.class,
            names = {"CLOUD", "GHEC"})
    void build_shouldWarnAndSucceed_whenFlavorIsNotGhesButBaseUriIsAGhesApi(final GitHubFlavor flavor) {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(TestHosts.GHES))
                .flavor(flavor);

        final GitHubClientConfiguration configuration;
        try (final LogCaptor logs = LogCaptor.forClass(GitHubClientConfiguration.class)) {
            configuration = builder.build();

            assertThat(logs.getWarnLogs()).singleElement().asString().contains(flavor.toString());
        }

        assertThat(configuration.flavor()).contains(flavor);
    }

    @Test
    void build_shouldNotWarn_whenFlavorMatchesTheHost() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(TestHosts.GITHUB))
                .flavor(GitHubFlavor.CLOUD);

        try (final LogCaptor logs = LogCaptor.forClass(GitHubClientConfiguration.class)) {
            builder.build();

            assertThat(logs.getWarnLogs()).isEmpty();
        }
    }

    private static GitHubClientConfiguration.Builder ghesBuilder(final GhesVersion version) {
        return GitHubClientConfiguration.builder()
                .configuration(HttpClientConfiguration.of(TestHosts.GHES))
                .flavor(GitHubFlavor.GHES)
                .ghesVersion(version);
    }

    @Test
    void defaults_shouldUseConditionalCacheLimits_whenCalled() {
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.defaults();

        assertSoftly(softly -> {
            softly.assertThat(configuration.conditionalCacheMaxEntries()).isEqualTo(64);
            softly.assertThat(configuration.conditionalCacheMaxBodyBytes()).isEqualTo(1024 * 1024);
            softly.assertThat(configuration.conditionalCacheMaxBytes()).isEqualTo(4L * 1024 * 1024);
        });
    }

    @Test
    void build_shouldRejectConditionalCacheEntries_whenZero() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().conditionalCacheMaxEntries(0);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conditionalCacheMaxEntries");
    }

    @Test
    void build_shouldRejectConditionalCacheBodyBytes_whenZero() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().conditionalCacheMaxBodyBytes(0);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conditionalCacheMaxBodyBytes");
    }

    @Test
    void build_shouldRejectConditionalCacheBytes_whenZero() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().conditionalCacheMaxBytes(0L);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conditionalCacheMaxBytes");
    }

    @Test
    void build_shouldRejectMutationInterval_whenBelowOneSecond() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().mutationInterval(Duration.ofMillis(999));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mutationInterval");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 250, 999})
    void build_shouldAcceptShorterMutationInterval_whenFlavorIsGhes(final long millis) {
        final GitHubClientConfiguration.Builder builder =
                ghesBuilder(new GhesVersion(3, 22)).mutationInterval(Duration.ofMillis(millis));

        final GitHubClientConfiguration configuration = builder.build();

        assertThat(configuration.mutationInterval()).isEqualTo(Duration.ofMillis(millis));
    }

    @Test
    void build_shouldRejectNegativeMutationInterval_whenFlavorIsGhes() {
        final GitHubClientConfiguration.Builder builder =
                ghesBuilder(new GhesVersion(3, 22)).mutationInterval(Duration.ofMillis(-1));

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mutationInterval");
    }

    @Test
    void build_shouldRejectMutationIntervalBelowOneSecond_whenFlavorIsGhec() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().flavor(GitHubFlavor.GHEC).mutationInterval(Duration.ZERO);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mutationInterval");
    }

    @Test
    void build_shouldAcceptMutationInterval_whenExactlyOneSecond() {
        final GitHubClientConfiguration.Builder builder =
                GitHubClientConfiguration.builder().mutationInterval(Duration.ofSeconds(1));

        final GitHubClientConfiguration configuration = builder.build();

        assertThat(configuration.mutationInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void builder_shouldKeepEverySetting_whenAllSet() {
        final HttpClientConfiguration configuration = TestClients.configuration(TestHosts.GHES);
        final HttpTransportConfiguration transport = HttpTransportConfiguration.builder()
                .maxResponseBytes(1024)
                .maxConcurrentRequests(4)
                .maxSlotWait(Duration.ofSeconds(5))
                .build();
        final RetryConfiguration retry =
                RetryConfiguration.builder().maxRateLimitAttempts(3).build();
        final GitHubRateLimitListener listener = mock(GitHubRateLimitListener.class);
        final HttpCallObserver observer = mock(HttpCallObserver.class);
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder()
                .configuration(configuration)
                .transport(transport)
                .retry(retry)
                .mutationInterval(Duration.ofSeconds(2))
                .conditionalCacheMaxEntries(8)
                .conditionalCacheMaxBodyBytes(2048)
                .conditionalCacheMaxBytes(4096L)
                .rateLimitListener(listener)
                .callObserver(observer)
                .allowInsecureHttp(true);

        final GitHubClientConfiguration settings = builder.build();

        assertSoftly(softly -> {
            softly.assertThat(settings.configuration()).isSameAs(configuration);
            softly.assertThat(settings.transport()).isSameAs(transport);
            softly.assertThat(settings.retry()).isSameAs(retry);
            softly.assertThat(settings.mutationInterval()).isEqualTo(Duration.ofSeconds(2));
            softly.assertThat(settings.conditionalCacheMaxEntries()).isEqualTo(8);
            softly.assertThat(settings.conditionalCacheMaxBodyBytes()).isEqualTo(2048);
            softly.assertThat(settings.conditionalCacheMaxBytes()).isEqualTo(4096L);
            softly.assertThat(settings.rateLimitListener()).isSameAs(listener);
            softly.assertThat(settings.callObserver()).isSameAs(observer);
            softly.assertThat(settings.allowInsecureHttp()).isTrue();
        });
    }

    @Test
    void builder_shouldRejectNull_whenSettingIsNull() {
        final GitHubClientConfiguration.Builder builder = GitHubClientConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.configuration(null)))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.transport(null))).isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.retry(null))).isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.mutationInterval(null)))
                    .isInstanceOf(NullPointerException.class);
        });
    }
}
