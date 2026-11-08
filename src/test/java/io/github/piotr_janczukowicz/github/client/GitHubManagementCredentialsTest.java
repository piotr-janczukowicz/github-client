package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class GitHubManagementCredentialsTest {

    @Test
    void constructor_shouldThrow_whenPasswordNull() {
        final Throwable thrown = catchThrowable(() -> new GitHubManagementCredentials(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void constructor_shouldThrow_whenPasswordBlank(final String password) {
        final Throwable thrown = catchThrowable(() -> new GitHubManagementCredentials(password));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("password");
    }

    @Test
    void authentication_shouldBeBasicForApiKey_whenPasswordGiven() {
        final GitHubManagementCredentials credentials = new GitHubManagementCredentials("secret");

        final String header = credentials.authentication().headerValue();

        assertThat(header).isEqualTo("Basic YXBpX2tleTpzZWNyZXQ=");
    }

    @Test
    void toString_shouldHidePassword() {
        final GitHubManagementCredentials credentials = new GitHubManagementCredentials("secret");

        assertThat(credentials.toString()).contains("password=<redacted>").doesNotContain("secret");
    }
}
