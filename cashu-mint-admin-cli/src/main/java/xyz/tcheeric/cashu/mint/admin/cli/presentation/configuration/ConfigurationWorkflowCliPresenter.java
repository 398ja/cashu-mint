package xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationWorkflowResponse;
import xyz.tcheeric.cashu.mint.admin.cli.io.OutputFormat;

/**
 * Delegates configuration workflow rendering to the appropriate presenter based on CLI output format.
 */
public final class ConfigurationWorkflowCliPresenter {

    private final ConfigurationWorkflowJsonPresenter jsonPresenter;
    private final ConfigurationWorkflowTablePresenter tablePresenter;

    public ConfigurationWorkflowCliPresenter(final ObjectMapper mapper) {
        this(new ConfigurationWorkflowJsonPresenter(mapper), new ConfigurationWorkflowTablePresenter(mapper));
    }

    public ConfigurationWorkflowCliPresenter(final ConfigurationWorkflowJsonPresenter jsonPresenter,
                                             final ConfigurationWorkflowTablePresenter tablePresenter) {
        this.jsonPresenter = Objects.requireNonNull(jsonPresenter, "jsonPresenter");
        this.tablePresenter = Objects.requireNonNull(tablePresenter, "tablePresenter");
    }

    public String present(final ConfigurationWorkflowResponse response, final OutputFormat format) {
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case JSON -> jsonPresenter.present(response);
            case TABLE -> tablePresenter.present(response);
        };
    }
}
