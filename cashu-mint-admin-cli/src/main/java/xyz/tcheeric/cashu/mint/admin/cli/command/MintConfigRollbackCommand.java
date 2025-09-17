package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;

import java.util.List;

@Command(name = "rollback",
         description = "Roll back to a previously applied configuration revision.",
         mixinStandardHelpOptions = true)
public final class MintConfigRollbackCommand
    extends ConfigurationWorkflowCliCommand<ManageConfigurationUseCase.RollbackConfigurationCommand> {

    @Option(names = "--target-revision",
            description = "Configuration revision to roll back to.")
    private String targetRevision;

    @Option(names = "--rollback-reason",
            description = "Reason for performing the rollback.")
    private String rollbackReason;

    @Option(names = "--audit-reference",
            description = "External audit reference documenting the rollback.")
    private String auditReference;

    public MintConfigRollbackCommand(final ManageConfigurationUseCase configurationUseCase,
                                     final CommandPayloadMapper payloadMapper,
                                     final ResponseRenderingService renderingService) {
        super(configurationUseCase, payloadMapper, renderingService);
    }

    @Override
    protected ManageConfigurationUseCase.ConfigurationWorkflowResponse invoke() {
        final ManageConfigurationUseCase.RollbackConfigurationCommand payload =
            readPayload(ManageConfigurationUseCase.RollbackConfigurationCommand.class).orElse(null);

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
        final String reason = requireNonBlank(
            coalesce(rollbackReason, payload != null ? payload.rollbackReason() : null, () -> null),
            "--rollback-reason"
        );
        final String auditRef = coalesce(auditReference, payload != null ? payload.auditReference() : null, () -> null);
        final String versionTag = coalesce(optionVersionTag(), payload != null ? payload.versionTag() : null, () -> null);
        final List<String> reasonCodes = coalesceList(optionReasonCodes(), payload != null ? payload.reasonCodes() : null);
        final List<String> ticketReferences = coalesceList(optionTicketReferences(),
            payload != null ? payload.ticketReferences() : null);
        final String requestId = coalesce(optionRequestId(), payload != null ? payload.requestId() : null, this::defaultRequestId);
        final String correlationId = coalesce(optionCorrelationId(), payload != null ? payload.correlationId() : null,
            this::defaultCorrelationId);

        final ManageConfigurationUseCase.RollbackConfigurationCommand command =
            new ManageConfigurationUseCase.RollbackConfigurationCommand(
                mintId,
                operatorId,
                revision,
                reason,
                auditRef,
                versionTag,
                reasonCodes,
                ticketReferences,
                requestId,
                correlationId
            );
        return configurationUseCase().rollback(command);
    }
}
