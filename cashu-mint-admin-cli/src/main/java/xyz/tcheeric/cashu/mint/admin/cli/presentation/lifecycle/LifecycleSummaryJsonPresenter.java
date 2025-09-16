package xyz.tcheeric.cashu.mint.admin.cli.presentation.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.cli.io.JsonResponseRenderer;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;

/**
 * Presents lifecycle summaries as pretty printed JSON.
 */
public final class LifecycleSummaryJsonPresenter {

    private final JsonResponseRenderer renderer;

    public LifecycleSummaryJsonPresenter(final ObjectMapper mapper) {
        this(new JsonResponseRenderer(mapper));
    }

    public LifecycleSummaryJsonPresenter(final JsonResponseRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public String present(final LifecycleSummary summary) {
        return renderer.render(summary);
    }
}
