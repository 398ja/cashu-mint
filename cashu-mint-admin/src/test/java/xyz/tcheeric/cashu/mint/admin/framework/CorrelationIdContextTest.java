package xyz.tcheeric.cashu.mint.admin.framework;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdContextTest {

    @AfterEach
    void tearDown() {
        CorrelationIdContext.clear();
        MDC.clear();
    }

    // Ensures a generated identifier is stored in the context and MDC.
    @Test
    void shouldGenerateCorrelationIdWhenMissing() {
        final String generated = CorrelationIdContext.init();

        assertThat(generated).isNotBlank();
        assertThat(CorrelationIdContext.currentId()).isEqualTo(generated);
        assertThat(MDC.get(CorrelationIdContext.MDC_KEY)).isEqualTo(generated);
    }

    // Verifies that supplied identifiers are normalised and reused.
    @Test
    void shouldReuseProvidedCorrelationId() {
        final String correlationId = CorrelationIdContext.init("  cli-request ");

        assertThat(correlationId).isEqualTo("cli-request");
        assertThat(CorrelationIdContext.currentId()).isEqualTo("cli-request");
    }

    // Confirms clear removes the identifier from thread locals and MDC.
    @Test
    void shouldClearCorrelationId() {
        CorrelationIdContext.init("rest-call");

        CorrelationIdContext.clear();

        assertThat(CorrelationIdContext.currentId()).isNull();
        assertThat(MDC.get(CorrelationIdContext.MDC_KEY)).isNull();
    }
}
