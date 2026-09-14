package xyz.tcheeric.cashu.mint.rest.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** Root package scanned for {@code @RestController} classes. */
    private static final String CONTROLLER_PACKAGE = "xyz.tcheeric.cashu.mint.rest";

    /**
     * Lower bound on the number of controllers the scan must find.
     *
     * <p>A scan that silently finds nothing is the failure mode this whole review kept running
     * into, so the count is asserted rather than trusted. The bound is deliberately below the
     * current count: it exists to catch a broken scan, not to need editing whenever a controller
     * is added.
     */
    private static final int MINIMUM_EXPECTED_CONTROLLERS = 5;

    /**
     * Every {@code @RestController} under {@link #CONTROLLER_PACKAGE}.
     *
     * <p>Discovered rather than listed. An earlier version of this rule enumerated the five
     * controllers by hand, which meant a sixth would have been outside the rule entirely -- the
     * new controller, the one most likely to repeat #424, would have been the one not checked.
     * Self-review caught that.
     */
    private static List<Class<?>> controllers() {
        // The scanner evaluates @Conditional metadata, so it must be given an environment in which
        // the conditions hold. Without this, VoucherProvenanceController and
        // VoucherForensicController -- both @ConditionalOnProperty(cashu.mint.jpa.enabled) -- were
        // silently dropped, and the rule checked three controllers while appearing to check all of
        // them. A conditionally-registered controller still serves requests wherever its condition
        // holds, so it is exactly as much in scope as any other. The count assertion below caught
        // this; overriding isCandidateComponent does not, because the condition check happens in a
        // private method that runs earlier.
        MockEnvironment environment = new MockEnvironment();
        CONDITIONAL_PROPERTIES.forEach(environment::setProperty);

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "scanned controller " + definition.getBeanClassName()
                                + " is not loadable; the rule cannot check it", e);
            }
        }
        return found;
    }

    /**
     * Properties that must be set for conditionally-registered controllers to be scanned.
     *
     * <p>These are not configuration for the test so much as a statement that the controllers
     * guarded by them are in scope. If a new {@code @ConditionalOnProperty} controller appears and
     * is not represented here, the controller count assertion fails rather than quietly skipping
     * it.
     */
    private static final Map<String, String> CONDITIONAL_PROPERTIES =
            Map.of("cashu.mint.jpa.enabled", "true");

    /** How deep to walk a DTO's field types looking for constraints. Bounds cyclic models. */
    private static final int MAX_DEPTH = 3;

    @Test
    void everyConstrainedRequestBodyIsValidated() {
        List<String> violations = new ArrayList<>();

        List<Class<?>> controllers = controllers();
        assertThat(controllers)
                .as("component scan of %s found %d controllers; a scan that finds nothing would "
                                + "make this rule pass by checking nothing",
                        CONTROLLER_PACKAGE, controllers.size())
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXPECTED_CONTROLLERS);

        for (Class<?> controller : controllers) {

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
