package xyz.tcheeric.cashu.mint.rest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.StandardEnvironment;

/**
 * The durable-persistence guard must stay armed everywhere money moves.
 *
 * <p>{@link DurablePersistenceStartupValidator} refuses to start when
 * {@code cashu.mint.jpa.enabled} is false, because without it melt pays the
 * Lightning invoice before recording its inputs as spent. Test contexts are
 * exempt, since they legitimately run without a datasource.
 *
 * <p>The exemption is expressed as a profile string, and profile strings are
 * easy to get subtly wrong. {@code !test} does <em>not</em> exclude
 * {@code websocket-test} — Spring matches names exactly rather than by prefix
 * — which left two IT classes failing to boot for months (#463). Fixing that
 * meant widening the exemption, and a widened exemption on a money-safety
 * guard is worth pinning: the failure mode is silent, and it is only visible
 * at the moment the guard was supposed to fire.
 *
 * <p>These assertions evaluate the real annotation expression against a real
 * {@link StandardEnvironment}, so they track the annotation rather than a
 * copy of it.
 */
@DisplayName("the durable-persistence guard is armed in every non-test profile")
class DurablePersistenceStartupValidatorProfileTest {

    private static final String EXPRESSION =
            DurablePersistenceStartupValidator.class.getAnnotation(Profile.class).value()[0];

    private static boolean guardIsArmedUnder(String... activeProfiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(activeProfiles);
        return env.acceptsProfiles(org.springframework.core.env.Profiles.of(EXPRESSION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging", "default", "prod,metrics", "staging,nostr"})
    @DisplayName("armed wherever real money moves")
    void armedInDeploymentProfiles(String profiles) {
        assertThat(guardIsArmedUnder(profiles.split(",")))
                .as("""
                        %s must keep the guard armed. With it disarmed and jpa.enabled=false, \
                        melt pays the invoice before checking whether its inputs were already \
                        spent, so an already-spent proof can result in a paid invoice.""",
                        profiles)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "websocket-test"})
    @DisplayName("stood down only for contexts that legitimately have no datasource")
    void standsDownForTestProfiles(String profile) {
        assertThat(guardIsArmedUnder(profile))
                .as("%s runs without a datasource by design; the guard must not fail its context",
                        profile)
                .isFalse();
    }

    /**
     * The exemption list is a denylist of exact names, so it can only be
     * widened deliberately. This fails if someone adds a profile to it, which
     * is the review moment that matters — each name is a context where the
     * money-safety check does not run.
     */
    @Test
    @DisplayName("the exemption list has not silently grown")
    void theExemptionListIsExactlyTheThreeKnownTestProfiles() {
        List<String> exempted = List.of("local", "test", "websocket-test");

        for (String profile : exempted) {
            assertThat(guardIsArmedUnder(profile)).isFalse();
        }

        assertThat(EXPRESSION)
                .as("""
                        Adding a profile here disarms a guard that prevents paying a Lightning \
                        invoice against already-spent proofs. If that is intended, update this \
                        test and say why in the annotation's comment — do not just widen the \
                        expression.""")
                .isEqualTo("!local & !test & !websocket-test");
    }

    /**
     * The mistake that caused #463, pinned directly: a profile whose name
     * merely contains an exempt name is not exempt.
     */
    @Test
    @DisplayName("prefix and substring matches do not disarm the guard")
    void namesThatMerelyResembleAnExemptProfileStayArmed() {
        assertThat(guardIsArmedUnder("test-prod"))
                .as("'test-prod' is not 'test'; Spring matches exactly, and so must this guard")
                .isTrue();
        assertThat(guardIsArmedUnder("production"))
                .as("'production' contains no exempt name")
                .isTrue();
        assertThat(guardIsArmedUnder("prod", "test"))
                .as("""
                        an explicitly test-flagged prod context is still exempt, which is \
                        surprising enough to be worth stating: the exemption wins when both \
                        are active, so nothing should ever activate 'test' in a deployment.""")
                .isFalse();
    }
}
