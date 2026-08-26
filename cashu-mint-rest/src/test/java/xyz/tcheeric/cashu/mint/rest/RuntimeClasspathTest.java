package xyz.tcheeric.cashu.mint.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that libraries Hibernate loads reflectively at boot are on the
 * <em>runtime</em> classpath, and therefore inside the shipped image.
 *
 * <p>These dependencies are invisible to ordinary tests. Hibernate resolves them
 * by name when it builds the EntityManagerFactory, so nothing references them at
 * compile time, and a {@code Class.forName} assertion would pass even when the
 * jar is missing from the image, because test-scoped copies sit on the test
 * classpath. This test reads the runtime classpath recorded by
 * maven-dependency-plugin instead, which is the same set the fat jar packages.
 */
class RuntimeClasspathTest {

    private static final Path RUNTIME_CLASSPATH = Path.of("target", "runtime-classpath.txt");

    /**
     * Hibernate's BytecodeProviderImpl loads ByteBuddy reflectively when the JPA
     * profile is active. When byte-buddy is test-scoped it is absent from the
     * image, and the mint applies every Flyway migration and then dies with
     * NoClassDefFoundError: net/bytebuddy/description/type/TypeDefinition.
     */
    @Test
    @DisplayName("ByteBuddy ships at runtime so Hibernate's bytecode provider can start")
    void byteBuddyIsOnTheRuntimeClasspath() throws IOException {
        assertThat(runtimeClasspathEntries())
                .as("byte-buddy must be on the runtime classpath; a test-scoped entry in "
                        + "dependencyManagement silently overrides Hibernate's compile-scope "
                        + "transitive dependency and drops it from the image")
                .anyMatch(entry -> entry.contains("byte-buddy"));
    }

    /**
     * Guards the pairing above: Hibernate needs both ByteBuddy and its Jandex
     * companion plus a JPA provider present to build an EntityManagerFactory.
     */
    @Test
    @DisplayName("Hibernate core ships at runtime alongside its bytecode provider")
    void hibernateCoreIsOnTheRuntimeClasspath() throws IOException {
        assertThat(runtimeClasspathEntries())
                .as("hibernate-core must ship whenever byte-buddy does, otherwise this "
                        + "test is guarding a dependency nothing uses")
                .anyMatch(entry -> entry.contains("hibernate-core"));
    }

    private List<String> runtimeClasspathEntries() throws IOException {
        assertThat(RUNTIME_CLASSPATH)
                .as("runtime classpath must be recorded by maven-dependency-plugin during "
                        + "process-test-resources; run through Maven rather than an IDE")
                .exists();
        String recorded = Files.readString(RUNTIME_CLASSPATH);
        return Arrays.asList(recorded.split(java.io.File.pathSeparator));
    }
}
