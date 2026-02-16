package xyz.tcheeric.cashu.mint.admin.cli.presentation.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.cli.io.TableResponseRenderer;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;

/**
 * Presents lifecycle summaries as human-readable tables.
 */
public final class LifecycleSummaryTablePresenter {

    private final TableResponseRenderer renderer;

    public LifecycleSummaryTablePresenter(final ObjectMapper mapper) {
        this(new TableResponseRenderer(mapper));
    }

    public LifecycleSummaryTablePresenter(final TableResponseRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public String present(final LifecycleSummary summary) {
        return renderer.render(summary);
    }
}
