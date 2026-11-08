package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class GitHubApiBaseTest {

    @Test
    void constructor_shouldThrow_whenUriNull() {
        final Throwable thrown = catchThrowable(() -> new GitHubApiBase(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("uri");
    }

    @ParameterizedTest
    @MethodSource("io.github.piotr_janczukowicz.test.TestHosts#apiBases")
    void isGhes_shouldBeTrueOnlyForTheGhesBase_whenHostIsAnyApiBase(final URI base) {
        final GitHubApiBase apiBase = new GitHubApiBase(base);

        assertThat(apiBase.isGhes()).isEqualTo(base.equals(TestHosts.GHES));
    }

    @ParameterizedTest
    @MethodSource("io.github.piotr_janczukowicz.test.TestHosts#apiBases")
    void consoleUri_shouldBeTheManageRootOfTheGhesHost_whenHostIsAnyApiBase(final URI base) {
        final Optional<URI> console = new GitHubApiBase(base).consoleUri();

        assertThat(console)
                .isEqualTo(
                        base.equals(TestHosts.GHES)
                                ? Optional.of(URI.create("https://ghe.example/manage/v1/"))
                                : Optional.empty());
    }

    @Test
    void uploadsHost_shouldBeNull_whenUriHasNoHost() {
        assertThat(new GitHubApiBase(URI.create("urn:example:api")).uploadsHost())
                .isNull();
    }

    @Test
    void uploadsHost_shouldNameTheUploadsHost_whenHostIsGithubOrDataResidency() {
        assertSoftly(softly -> {
            softly.assertThat(new GitHubApiBase(TestHosts.GITHUB).uploadsHost()).isEqualTo("uploads.github.com");
            softly.assertThat(new GitHubApiBase(TestHosts.GHEC).uploadsHost()).isEqualTo("uploads.octocorp.ghe.com");
            softly.assertThat(new GitHubApiBase(TestHosts.GHES).uploadsHost()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.ghe.com/",
        "https://octocorp.ghe.com/",
        "https://api.octocorp.example.com/",
        "https://ghe.example/prefix/api/v3/"
    })
    void uploadsHost_shouldBeNull_whenHostIsNeitherGithubNorDataResidency(final URI base) {
        assertThat(new GitHubApiBase(base).uploadsHost()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "https://ghe.example/api/v3/,/api/uploads/repos/o/r,true",
        "https://ghe.example/api/v3/,/api/v3/repos/o/r,false",
        "https://api.github.com/,/api/uploads/repos/o/r,false",
        "https://ghe.example/prefix/api/v3/,/api/uploads/repos/o/r,false"
    })
    void isGhesUploadsPath_shouldBeTrueOnlyBelowTheUploadsPathOfAGhesBase(
            final URI base, final String encodedPath, final boolean expected) {
        assertThat(new GitHubApiBase(base).isGhesUploadsPath(encodedPath)).isEqualTo(expected);
    }
}
