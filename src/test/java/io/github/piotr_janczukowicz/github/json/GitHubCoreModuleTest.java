package io.github.piotr_janczukowicz.github.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubCoreModuleTest {

    @Test
    void create_shouldNameTheCoreModule_whenCalled() {
        final String name = GitHubCoreModule.create().getModuleName();

        assertThat(name).isEqualTo("GitHubCore");
    }

    @Test
    void constructor_shouldThrow_whenCalledByReflection() throws ReflectiveOperationException {
        final Constructor<GitHubCoreModule> constructor = GitHubCoreModule.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final Throwable thrown = catchThrowable(constructor::newInstance);

        assertThat(thrown)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
