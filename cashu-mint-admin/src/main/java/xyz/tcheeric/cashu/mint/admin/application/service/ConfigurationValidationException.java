package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationWorkflowResponse;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.ValidationReport;

/**
 * Exception raised when configuration validation fails.
 */
public class ConfigurationValidationException extends RuntimeException {

    private final String mintId;
    private final long revisionId;
    private final ValidationReport report;
    private final ConfigurationWorkflowResponse response;

    public ConfigurationValidationException(final MintId mintId,
                                            final ConfigurationRevisionId revisionId,
                                            final ValidationReport report,
                                            final ConfigurationWorkflowResponse response) {
        super("Validation failed for configuration revision %s of mint %s".formatted(revisionId.value(), mintId.asString()));
        this.mintId = mintId.asString();
        this.revisionId = revisionId.value();
        this.report = report;
        this.response = response;
    }

    public String mintId() {
        return mintId;
    }

    public long revisionId() {
        return revisionId;
    }

    public ValidationReport report() {
        return report;
    }

    public ConfigurationWorkflowResponse response() {
        return response;
    }
}
