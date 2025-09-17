package xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationWorkflowResponse;
import xyz.tcheeric.cashu.mint.admin.cli.io.JsonResponseRenderer;

/**
 * Presents configuration workflow responses as prettified JSON with secret redaction applied.
 */
final class ConfigurationWorkflowJsonPresenter {

    private final JsonResponseRenderer renderer;
    private final ConfigurationWorkflowViewFactory viewFactory;

    ConfigurationWorkflowJsonPresenter(final ObjectMapper mapper) {
        this(new JsonResponseRenderer(mapper), new ConfigurationWorkflowViewFactory());
    }

    ConfigurationWorkflowJsonPresenter(final JsonResponseRenderer renderer,
                                       final ConfigurationWorkflowViewFactory viewFactory) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.viewFactory = Objects.requireNonNull(viewFactory, "viewFactory");
    }

    String present(final ConfigurationWorkflowResponse response) {
        if (response == null) {
            return "";
        }
        return renderer.render(viewFactory.build(response));
    }
}
