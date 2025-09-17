package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;

import java.util.List;

@Command(name = "apply",
         description = "Apply an approved configuration revision.",
         mixinStandardHelpOptions = true)
public final class MintConfigApplyCommand
    extends ConfigurationWorkflowCliCommand<ManageConfigurationUseCase.ApplyConfigurationCommand> {

    @Option(names = "--target-revision",
            description = "Configuration revision to apply.")
    private String targetRevision;

    @Option(names = "--deployment-ticket",
            description = "Deployment ticket authorising the change.")
    private String deploymentTicket;

    public MintConfigApplyCommand(final ManageConfigurationUseCase configurationUseCase,
                                  final CommandPayloadMapper payloadMapper,
                                  final ResponseRenderingService renderingService) {
        super(configurationUseCase, payloadMapper, renderingService);
    }

    @Override
    protected ManageConfigurationUseCase.ConfigurationWorkflowResponse invoke() {
        final ManageConfigurationUseCase.ApplyConfigurationCommand payload =
            readPayload(ManageConfigurationUseCase.ApplyConfigurationCommand.class).orElse(null);

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
        final String ticket = requireNonBlank(
            coalesce(deploymentTicket, payload != null ? payload.deploymentTicket() : null, () -> null),
            "--deployment-ticket"
        );
        final String versionTag = coalesce(optionVersionTag(), payload != null ? payload.versionTag() : null, () -> null);
        final List<String> reasonCodes = coalesceList(optionReasonCodes(), payload != null ? payload.reasonCodes() : null);
        final List<String> ticketReferences = coalesceList(optionTicketReferences(),
            payload != null ? payload.ticketReferences() : null);
        final String requestId = coalesce(optionRequestId(), payload != null ? payload.requestId() : null, this::defaultRequestId);
        final String correlationId = coalesce(optionCorrelationId(), payload != null ? payload.correlationId() : null,
            this::defaultCorrelationId);

        final ManageConfigurationUseCase.ApplyConfigurationCommand command =
            new ManageConfigurationUseCase.ApplyConfigurationCommand(
                mintId,
                operatorId,
                revision,
                ticket,
                versionTag,
                reasonCodes,
                ticketReferences,
                requestId,
                correlationId
            );
        return configurationUseCase().apply(command);
    }
}
