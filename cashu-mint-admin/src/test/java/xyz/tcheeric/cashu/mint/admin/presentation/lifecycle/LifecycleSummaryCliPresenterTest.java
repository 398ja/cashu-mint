package xyz.tcheeric.cashu.mint.admin.presentation.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.cli.io.OutputFormat;

/**
 * Verifies CLI lifecycle presenters reuse shared rendering logic.
 */
class LifecycleSummaryCliPresenterTest {

    private final LifecycleSummaryCliPresenter presenter =
        new LifecycleSummaryCliPresenter(new ObjectMapper().findAndRegisterModules());

    // Ensures JSON output mirrors the shared lifecycle summary structure.
    @Test
    void shouldRenderJsonSummary() {
        final LifecycleSummary summary = new LifecycleSummary(LifecycleAction.CREATE,
            "mint-123", null, "PROVISIONED", "v1", true, "Mint created");

        final String rendered = presenter.present(summary, OutputFormat.JSON);

        assertThat(rendered).contains("\"operation\"", "\"CREATE\"", "\"Mint created\"");
    }

    // Ensures table output includes the lifecycle state and message fields.
    @Test
    void shouldRenderTableSummary() {
        final LifecycleSummary summary = new LifecycleSummary(LifecycleAction.RESUME,
            "mint-456", "SUSPENDED", "ACTIVE", "ticket-9", true, "Mint resumed");

        final String rendered = presenter.present(summary, OutputFormat.TABLE);

        assertThat(rendered).contains("ACTIVE", "Mint resumed");
    }
}
