package xyz.tcheeric.cashu.mint.proto.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaField;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

/**
 * FR-009 / Constitution Principle I gate: financial-amount fields in the
 * mint's validation packages MUST use {@code long}, never {@code int} or
 * {@code Integer}. This test fails the build if a regression lands.
 *
 * <p>Names that count as "amount-bearing": {@code amount}, {@code fee},
 * {@code feeReserve}, anything ending in {@code Amount} (case-insensitive).
 * The check applies to packages where validation lives:
 * {@code tasks}, {@code ports}, and {@code domain} (when added).
 */
class LongArithmeticArchTest {

    private static final Set<String> FORBIDDEN_INT_TYPES = Set.of("int", "java.lang.Integer");

    @Test
    void amountFieldsMustNotBeInt() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "xyz.tcheeric.cashu.mint.proto.tasks",
                        "xyz.tcheeric.cashu.mint.proto.ports",
                        "xyz.tcheeric.cashu.mint.proto.domain"); // spec 002 T904

        ArchRule rule = fields()
                .that(new com.tngtech.archunit.base.DescribedPredicate<>("are amount-bearing") {
                    @Override
                    public boolean test(JavaField input) {
                        String name = input.getName().toLowerCase(Locale.ROOT);
                        return name.equals("amount")
                                || name.equals("fee")
                                || name.equals("feereserve")
                                || name.endsWith("amount");
                    }
                })
                .should(new ArchCondition<JavaField>("not be of type int or Integer") {
                    @Override
                    public void check(JavaField field, ConditionEvents events) {
                        String type = field.getRawType().getName();
                        if (FORBIDDEN_INT_TYPES.contains(type)) {
                            events.add(SimpleConditionEvent.violated(field,
                                    "Field " + field.getFullName()
                                            + " is of type " + type
                                            + " but spec 001 FR-009 requires `long` for amount-bearing fields"));
                        }
                    }
                });

        rule.check(classes);
    }
}
