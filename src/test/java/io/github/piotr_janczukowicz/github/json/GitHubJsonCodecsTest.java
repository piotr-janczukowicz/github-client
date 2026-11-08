package io.github.piotr_janczukowicz.github.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubJsonCodecsTest {

    @Test
    void defaults_shouldReturnSameInstance_whenCalledTwice() {
        final JsonCodec first = GitHubJsonCodecs.defaults();

        final JsonCodec second = GitHubJsonCodecs.defaults();

        assertThat(first).isSameAs(second);
    }

    @Test
    void constructor_shouldRejectInstantiation_whenCalledViaReflection() throws Exception {
        final Constructor<GitHubJsonCodecs> constructor = GitHubJsonCodecs.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }
}
