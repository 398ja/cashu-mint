package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AutomationContextTest {

    @Test
    // Ensures automated contexts retain normalized values.
    void shouldCreateAutomationContextForAutomatedRun() {
        final AutomationContext context = new AutomationContext(true, "  orchestrator  ", " run-7 ");

        assertThat(context.automated()).isTrue();
        assertThat(context.system()).isEqualTo("orchestrator");
        assertThat(context.runId()).isEqualTo("run-7");
    }

    @Test
    // Ensures manual contexts reuse the canonical representation.
    void shouldProvideManualContext() {
        final AutomationContext context = AutomationContext.manual();

        assertThat(context.automated()).isFalse();
        assertThat(context.system()).isNull();
        assertThat(context.runId()).isNull();
        assertThat(context).isEqualTo(AutomationContext.manual());
    }

    @Test
    // Ensures automated contexts require the triggering system name.
    void shouldRejectAutomatedContextWithoutSystem() {
        assertThrows(IllegalArgumentException.class, () -> new AutomationContext(true, " ", "run"));
    }
}
