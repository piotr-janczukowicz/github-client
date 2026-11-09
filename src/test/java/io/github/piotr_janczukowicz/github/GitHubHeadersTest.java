package io.github.piotr_janczukowicz.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@UnitTest
class GitHubHeadersTest {

    @Test
    void constructor_shouldThrowUnsupportedOperationException_whenInvoked() throws NoSuchMethodException {
        final Constructor<GitHubHeaders> constructor = GitHubHeaders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class)
                .hasRootCauseMessage("Utility class");
    }

    @ParameterizedTest
    @CsvSource({
        "API_VERSION, X-GitHub-Api-Version",
        "REQUEST_ID, X-GitHub-Request-Id",
        "SSO, X-GitHub-SSO",
        "RATE_LIMIT_LIMIT, X-RateLimit-Limit",
        "RATE_LIMIT_REMAINING, X-RateLimit-Remaining",
        "RATE_LIMIT_USED, X-RateLimit-Used",
        "RATE_LIMIT_RESET, X-RateLimit-Reset",
        "RATE_LIMIT_RESOURCE, X-RateLimit-Resource"
    })
    void constant_shouldHoldCanonicalName_whenNamed(final String constantName, final String expectedName)
            throws ReflectiveOperationException {
        final String actual =
                (String) GitHubHeaders.class.getField(constantName).get(null);

        assertThat(actual).isEqualTo(expectedName);
    }

    @ParameterizedTest
    @CsvSource({
        "X-GitHub-Api-Version",
        "X-GitHub-Request-Id",
        "X-GitHub-SSO",
        "X-RateLimit-Limit",
        "X-RateLimit-Remaining",
        "X-RateLimit-Used",
        "X-RateLimit-Reset",
        "X-RateLimit-Resource"
    })
    void constant_shouldBeFoundCaseInsensitively_whenUsedAsHeaderName(final String name) {
        final HttpHeaders headers = HttpHeaders.of(name, "v");

        assertThat(headers.get(name.toLowerCase(Locale.ROOT))).contains("v");
    }
}
