package io.github.piotr_janczukowicz.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaPackage;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.testsupport.architecture.ImportedClasses;
import io.github.piotr_janczukowicz.testsupport.architecture.ModelArchitectureRules;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@UnitTest
class ArchitectureTest {

    private static final String GITHUB = "io.github.piotr_janczukowicz.github";

    private static JavaClasses production;

    @BeforeAll
    static void importClasses() {
        production = new ImportedClasses("io.github.piotr_janczukowicz")
                .all()
                .that(ImportedClasses.outsideTests().and(ImportedClasses.outsideTestSupport()))
                .that(ImportedClasses.inPackages(GITHUB));
    }

    @Test
    void transport_shouldNotDependOnTheGeneratedApis() {
        noClasses()
                .that()
                .resideInAPackage(GITHUB + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(GITHUB + ".api..", GITHUB + ".rest..", GITHUB + ".graphql..")
                .as("the transport does not know the REST or GraphQL libraries built on it")
                .check(production);
    }

    @Test
    void clientPackages_shouldHaveNoCycles() {
        slices().matching(GITHUB + ".client.(*)..").should().beFreeOfCycles().check(production);
    }

    @Test
    void productionPackages_shouldBeNullMarked() {
        final Set<JavaPackage> packages = new LinkedHashSet<>();
        production.forEach(clazz -> packages.add(clazz.getPackage()));

        final List<String> unmarked = packages.stream()
                .filter(pkg -> !pkg.isAnnotatedWith(NullMarked.class))
                .map(JavaPackage::getName)
                .toList();

        assertThat(unmarked).as("packages without @NullMarked").isEmpty();
    }

    @Test
    void production_shouldHaveNoTestClasses() {
        ModelArchitectureRules.productionHasNoTestClasses().check(production);
    }

    @Test
    void testClasses_shouldCarryATagThatMavenRuns() {
        final JavaClasses all = new ImportedClasses("io.github.piotr_janczukowicz").all();
        final List<String> untagged = all.stream()
                .filter(clazz -> !clazz.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(clazz -> clazz.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith("org.junit.jupiter.api.Test")))
                .filter(clazz -> !clazz.isAnnotatedWith("io.github.piotr_janczukowicz.testsupport.UnitTest")
                        && !clazz.isAnnotatedWith("io.github.piotr_janczukowicz.testsupport.IntegrationTest")
                        && !clazz.isAnnotatedWith("org.junit.jupiter.api.Tag"))
                .map(JavaClass::getName)
                .toList();

        assertThat(untagged)
                .as("test classes without @UnitTest, @IntegrationTest or @Tag")
                .isEmpty();
    }
}
