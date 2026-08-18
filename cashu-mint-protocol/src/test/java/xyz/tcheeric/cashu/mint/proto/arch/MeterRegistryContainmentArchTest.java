package xyz.tcheeric.cashu.mint.proto.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Issue #343 — the protocol module must not be able to reach a
 * {@code MeterRegistry} at all.
 *
 * <p>The original defect behind #337 was that domain code took a raw registry
 * from a static service locator: a metric could be named at any call site,
 * with nothing tying the declaration to a consumer in either direction. The
 * accessor was deleted rather than deprecated, because a deprecated one
 * preserves the exact hole. This test is what stops the next spec from adding
 * counter number seventeen inline.
 *
 * <p>Metrics reach domain code only through the typed recorder ports in
 * {@code xyz.tcheeric.cashu.mint.proto.metrics}, whose Micrometer
 * implementations live in {@code cashu-mint-observability} — the one module
 * allowed to know that Micrometer exists.
 */
class MeterRegistryContainmentArchTest {

    /** No protocol class may depend on Micrometer's registry or meter types. */
    @Test
    void protocolCodeCannotReachAMeterRegistry() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("xyz.tcheeric.cashu.mint.proto");

        noClasses()
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("metrics must be emitted through the typed recorder ports in "
                        + "xyz.tcheeric.cashu.mint.proto.metrics; a raw MeterRegistry lets any "
                        + "call site invent a metric name (issue #337 root cause, closed by #343)")
                .check(classes);
    }
}
