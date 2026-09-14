package xyz.tcheeric.cashu.mint.rest.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @RequestBody} whose type declares Bean Validation constraints must also carry
 * {@code @Valid} (AppSec finding, issue #435).
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The High finding of the September 2026 review (#424) was not an exotic bug.
 * {@code PostRestoreRequest} and {@code PostCheckStateRequest} declared {@code @Size(max = 1000)}
 * limits, and {@code CashuController} bound both bodies without {@code @Valid}, so the
 * constraints were never consulted. Two unauthenticated endpoints were unbounded for as long as
 * that mismatch existed — and nothing failed to compile, no test failed, and code review did not
 * catch it.
 *
 * <p>The failure is structural: the DTOs live in {@code cashu-lib-entities} and the binding
 * happens here, so a constraint can be declared in one module and silently ignored in another.
 * Nothing connects the two except a developer remembering. This test is that connection.
 *
 * <h2>Why reflection rather than ArchUnit</h2>
 *
 * <p>ArchUnit would express this too, but it is a new dependency for a rule this test states in
 * about forty lines. The interesting part is not the traversal, it is that the constraint may sit
 * on a <em>field</em> of the DTO rather than on the DTO itself — which is exactly the case in
 * #424, where {@code PostRestoreRequest.blindedMessages} carries the {@code @Size}. A rule that
 * only inspected the parameter type's own annotations would have missed the very bug it is meant
 * to prevent.
 */
class RequestBodyValidationRuleTest {

    /**
     * Controllers scanned by this rule. Listed explicitly rather than discovered from the
     * classpath: a scan that silently finds nothing is the failure mode this whole review kept
     * running into, and an explicit list fails loudly when a class is renamed.
     */
    private static final List<Class<?>> CONTROLLERS = List.of(
            CashuController.class,
            VoucherController.class,
            VoucherProvenanceController.class,
            xyz.tcheeric.cashu.mint.rest.controller.admin.MeltSagaAdminController.class,
            xyz.tcheeric.cashu.mint.rest.controller.admin.VoucherForensicController.class);

    /** How deep to walk a DTO's field types looking for constraints. Bounds cyclic models. */
    private static final int MAX_DEPTH = 3;

    @Test
    void everyConstrainedRequestBodyIsValidated() {
        List<String> violations = new ArrayList<>();

        for (Class<?> controller : CONTROLLERS) {
            assertThat(controller.isAnnotationPresent(RestController.class))
                    .as("%s should be a @RestController; the rule is scanning the wrong class",
                            controller.getSimpleName())
                    .isTrue();

            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(RequestBody.class)) {
                        continue;
                    }
                    if (!declaresConstraints(parameter.getType(), new HashSet<>(), 0)) {
                        continue;
                    }
                    if (!parameter.isAnnotationPresent(Valid.class)) {
                        violations.add(controller.getSimpleName() + "." + method.getName()
                                + "(" + parameter.getType().getSimpleName() + ") "
                                + "binds a constrained body without @Valid");
                    }
                }
            }
        }

        assertThat(violations)
                .as("A @Size or @NotNull that nothing applies is not a limit, it is a comment. "
                        + "Add @Valid to the parameter, or drop the constraint from the DTO.")
                .isEmpty();
    }

    /**
     * Confirms the rule can actually see the constraint that #424 turned on.
     *
     * <p>Without this, a traversal bug would make {@link #everyConstrainedRequestBodyIsValidated}
     * pass by finding nothing to check — the same shape of failure as the vulnerability itself.
     */
    @Test
    void theRuleDetectsAFieldLevelConstraint() {
        assertThat(declaresConstraints(ConstrainedByField.class, new HashSet<>(), 0))
                .as("a constraint on a field must count, since that is where #424's @Size sat")
                .isTrue();
        assertThat(declaresConstraints(NoConstraints.class, new HashSet<>(), 0))
                .as("a type with no constraints must not be flagged")
                .isFalse();
    }

    /** Confirms the rule would have failed on the pre-#424 code, rather than passing vacuously. */
    @Test
    void theRuleWouldHaveCaughtTheOriginalDefect() {
        List<String> violations = new ArrayList<>();
        for (Method method : UnvalidatedControllerFixture.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(RequestBody.class)
                        && declaresConstraints(parameter.getType(), new HashSet<>(), 0)
                        && !parameter.isAnnotationPresent(Valid.class)) {
                    violations.add(method.getName());
                }
            }
        }
        assertThat(violations)
                .as("the rule must flag a constrained body bound without @Valid")
                .containsExactly("restore");
    }

    /**
     * Whether this type, or any type reachable from its fields, declares a Bean Validation
     * constraint.
     */
    private static boolean declaresConstraints(Class<?> type, Set<Class<?>> seen, int depth) {
        if (type == null || depth > MAX_DEPTH || !seen.add(type)
                || type.isPrimitive() || type.getName().startsWith("java.")) {
            return false;
        }
        if (hasConstraintAnnotation(type.getAnnotations())) {
            return true;
        }
        for (Field field : type.getDeclaredFields()) {
            if (hasConstraintAnnotation(field.getAnnotations())) {
                return true;
            }
            if (declaresConstraints(field.getType(), seen, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any annotation comes from {@code jakarta.validation.constraints}.
     *
     * <p>Matched by package rather than by an enumerated list, so a constraint this code has
     * never heard of still counts. Enumerating them would mean the rule quietly stops covering
     * whatever gets used next.
     */
    private static boolean hasConstraintAnnotation(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().getName()
                    .startsWith("jakarta.validation.constraints.")) {
                return true;
            }
        }
        return false;
    }

    // --- fixtures -------------------------------------------------------------------------

    /** A DTO whose constraint sits on a field, as PostRestoreRequest's @Size does. */
    private static final class ConstrainedByField {
        @Size(max = 10)
        @NotNull
        private List<String> items;
    }

    /** A DTO with no constraints at all. */
    private static final class NoConstraints {
        private String name;
    }

    /** Reproduces the pre-#424 shape: a constrained body bound without @Valid. */
    @SuppressWarnings("unused")
    private static final class UnvalidatedControllerFixture {

        void restore(@RequestBody ConstrainedRequest request) {
        }

        void alreadyValidated(@Valid @RequestBody ConstrainedRequest request) {
        }

        void unconstrained(@RequestBody NoConstraints request) {
        }

        private static final class ConstrainedRequest {
            @NotEmpty
            @Size(max = 1000)
            private List<String> outputs;
        }
    }
}
