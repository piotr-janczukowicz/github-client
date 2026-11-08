package io.github.piotr_janczukowicz.github.client.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubInstallationAuthConfigurationTest {

    @Test
    void defaults_shouldUseDocumentedRenewalTiming_whenNothingSet() {
        final GitHubInstallationAuthConfiguration configuration = validBuilder().build();

        assertSoftly(softly -> {
            softly.assertThat(configuration.refreshMargin()).isEqualTo(Duration.ofMinutes(5));
            softly.assertThat(configuration.initialFailureBackoff()).isEqualTo(Duration.ofSeconds(1));
            softly.assertThat(configuration.maxFailureBackoff()).isEqualTo(Duration.ofSeconds(30));
            softly.assertThat(configuration.minRenewInterval()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void build_shouldRequireAppId_whenAppIdNotSet() {
        final GitHubInstallationAuthConfiguration.Builder builder =
                GitHubInstallationAuthConfiguration.builder().installationId(1).privateKey(privateKey());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("appId");
    }

    @Test
    void build_shouldRequireInstallation_whenInstallationNotSet() {
        final GitHubInstallationAuthConfiguration.Builder builder =
                GitHubInstallationAuthConfiguration.builder().appId("12345").privateKey(privateKey());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("installationId");
    }

    @Test
    void build_shouldRequireExactlyOneKey_whenNoKeyGiven() {
        final GitHubInstallationAuthConfiguration.Builder builder =
                GitHubInstallationAuthConfiguration.builder().appId("12345").installationId(1);

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("privateKey");
    }

    @Test
    void build_shouldRejectBothKeys_whenPemAndKeyGiven() {
        final GitHubInstallationAuthConfiguration.Builder builder =
                validBuilder().privateKey(privateKey());

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("privateKey");
    }

    @Test
    void build_shouldRejectInvalidPem_whenPemIsNotAKey() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder()
                .appId("12345")
                .installationId(1)
                .privateKeyPem("nope");

        final Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appId_shouldRejectInvalidCharacters_whenAppIdHasQuote() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.appId("a\"b"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installationId_shouldRejectZero_whenNotPositive() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.installationId(0));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshMargin_shouldRejectNegative_whenNegative() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.refreshMargin(Duration.ofSeconds(-1)));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failureBackoff_shouldRejectInvalidArguments_whenBoundsAreWrong() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.failureBackoff(null, Duration.ofSeconds(1))))
                    .isInstanceOf(NullPointerException.class);
            softly.assertThat(catchThrowable(() -> builder.failureBackoff(Duration.ZERO, Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(
                            catchThrowable(() -> builder.failureBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void minRenewInterval_shouldRejectNegative_whenNegative() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        final Throwable thrown = catchThrowable(() -> builder.minRenewInterval(Duration.ofSeconds(-1)));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repositories_shouldRejectInvalidNames_whenEmptyOrBlank() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.repositories(List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.repositories(List.of(" "))))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.repositories(null)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void repositoryIds_shouldRejectInvalidIds_whenEmptyOrNotPositive() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.repositoryIds(List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.repositoryIds(List.of(0L))))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.repositoryIds(null)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void permissions_shouldRejectInvalidEntries_whenEmptyOrBlank() {
        final GitHubInstallationAuthConfiguration.Builder builder = GitHubInstallationAuthConfiguration.builder();

        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> builder.permissions(Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.permissions(Map.of("issues", " "))))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.permissions(Map.of(" ", "read"))))
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(catchThrowable(() -> builder.permissions(null))).isInstanceOf(NullPointerException.class);
        });
    }

    private static GitHubInstallationAuthConfiguration.Builder validBuilder() {
        return GitHubInstallationAuthConfiguration.builder()
                .appId("Iv1.test")
                .privateKeyPem(AppKeys.pkcs1Pem())
                .installationId(42);
    }

    private static PrivateKey privateKey() {
        return AppKeys.pair().getPrivate();
    }
}
