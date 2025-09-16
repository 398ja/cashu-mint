package xyz.tcheeric.cashu.mint.admin.presentation.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies lifecycle summary presenter messaging.
 */
class LifecycleSummaryPresenterTest {

    private final LifecycleSummaryPresenter presenter = new LifecycleSummaryPresenter();

    // Ensures success messages are rendered for changed lifecycle operations.
    @Test
    void shouldRenderChangedMessage() {
        final LifecycleSummary summary = presenter.present(new LifecycleSummaryPresenter.LifecycleSummaryRequest(
            LifecycleAction.UPDATE,
            "mint-001",
            "ACTIVE",
            "ACTIVE",
            "v2",
            true));

        assertThat(summary.message()).isEqualTo("Mint updated");
        assertThat(summary.changed()).isTrue();
    }

    // Ensures idempotent lifecycle operations reuse the shared messaging template.
    @Test
    void shouldRenderIdempotentMessage() {
        final LifecycleSummary summary = presenter.present(new LifecycleSummaryPresenter.LifecycleSummaryRequest(
            LifecycleAction.PAUSE,
            "mint-002",
            "SUSPENDED",
            "SUSPENDED",
            "v1",
            false));

        assertThat(summary.message()).isEqualTo("Mint already suspended");
        assertThat(summary.idempotent()).isTrue();
    }
}
