package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;

import java.util.List;

@Command(name = "preview",
         description = "Preview the configuration diff and validation results for a revision.",
         mixinStandardHelpOptions = true)
public final class MintConfigPreviewCommand
    extends ConfigurationWorkflowCliCommand<ManageConfigurationUseCase.PreviewConfigurationCommand> {

    @Option(names = "--target-revision",
            description = "Configuration revision to preview.")
    private String targetRevision;

    @Option(names = "--include-validation",
            description = "Include validation results in the preview output.",
            negatable = true)
    private Boolean includeValidation;

    public MintConfigPreviewCommand(final ManageConfigurationUseCase configurationUseCase,
                                    final CommandPayloadMapper payloadMapper,
                                    final ResponseRenderingService renderingService) {
        super(configurationUseCase, payloadMapper, renderingService);
    }

    @Override
    protected ManageConfigurationUseCase.ConfigurationWorkflowResponse invoke() {
        final ManageConfigurationUseCase.PreviewConfigurationCommand payload =
            readPayload(ManageConfigurationUseCase.PreviewConfigurationCommand.class).orElse(null);

        final String mintId = requireNonBlank(
            coalesce(optionMintId(), payload != null ? payload.mintId() : null, () -> DEFAULT_MINT_ID),
            "--mint-id"
        );
        final String operatorId = requireNonBlank(
            coalesce(optionOperatorId(), payload != null ? payload.operatorId() : null, () -> null),
            "--operator-id"
        );
        final String revision = requireNonBlank(
            coalesce(targetRevision, payload != null ? payload.targetRevision() : null, () -> null),
            "--target-revision"
        );
        final boolean validation = includeValidation != null ? includeValidation.booleanValue()
            : payload != null && payload.includeValidation();
        final String versionTag = coalesce(optionVersionTag(), payload != null ? payload.versionTag() : null, () -> null);
        final List<String> reasonCodes = coalesceList(optionReasonCodes(), payload != null ? payload.reasonCodes() : null);
        final List<String> ticketReferences = coalesceList(optionTicketReferences(),
            payload != null ? payload.ticketReferences() : null);
        final String requestId = coalesce(optionRequestId(), payload != null ? payload.requestId() : null, this::defaultRequestId);
        final String correlationId = coalesce(optionCorrelationId(), payload != null ? payload.correlationId() : null,
            this::defaultCorrelationId);

        final ManageConfigurationUseCase.PreviewConfigurationCommand command =
            new ManageConfigurationUseCase.PreviewConfigurationCommand(
                mintId,
                operatorId,
                revision,
                validation,
                versionTag,
                reasonCodes,
                ticketReferences,
                requestId,
                correlationId
            );
        return configurationUseCase().preview(command);
    }
}
