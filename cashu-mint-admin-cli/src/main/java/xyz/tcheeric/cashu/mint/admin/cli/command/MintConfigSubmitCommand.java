package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;

import java.util.ArrayList;
import java.util.List;

@Command(name = "submit",
         description = "Submit a configuration revision for review.",
         mixinStandardHelpOptions = true)
public final class MintConfigSubmitCommand
    extends ConfigurationWorkflowCliCommand<ManageConfigurationUseCase.SubmitConfigurationCommand> {

    @Option(names = "--approval-checklist",
            description = "Checklist items satisfied before approval. Repeatable.",
            split = ",")
    private List<String> approvalChecklist = new ArrayList<>();

    public MintConfigSubmitCommand(final ManageConfigurationUseCase configurationUseCase,
                                   final CommandPayloadMapper payloadMapper,
                                   final ResponseRenderingService renderingService) {
        super(configurationUseCase, payloadMapper, renderingService);
    }

    @Override
    protected ManageConfigurationUseCase.ConfigurationWorkflowResponse invoke() {
        final ManageConfigurationUseCase.SubmitConfigurationCommand payload =
            readPayload(ManageConfigurationUseCase.SubmitConfigurationCommand.class).orElse(null);

        final ManageConfigurationUseCase.ConfigurationPayload configurationPayload =
            payload != null && payload.payload() != null ? payload.payload() : defaultPayload();

        final String mintId = requireNonBlank(
            coalesce(optionMintId(), payload != null ? payload.mintId() : null, () -> DEFAULT_MINT_ID),
            "--mint-id"
        );
        final String operatorId = requireNonBlank(
            coalesce(optionOperatorId(), payload != null ? payload.operatorId() : null, () -> null),
            "--operator-id"
        );
        final String versionTag = coalesce(optionVersionTag(), payload != null ? payload.versionTag() : null, () -> null);
        final List<String> reasonCodes = coalesceList(optionReasonCodes(), payload != null ? payload.reasonCodes() : null);
        final List<String> ticketReferences = coalesceList(optionTicketReferences(),
            payload != null ? payload.ticketReferences() : null);
        final List<String> checklist = coalesceList(approvalChecklist,
            payload != null ? payload.approvalChecklist() : null);
        final String requestId = coalesce(optionRequestId(), payload != null ? payload.requestId() : null, this::defaultRequestId);
        final String correlationId = coalesce(optionCorrelationId(), payload != null ? payload.correlationId() : null,
            this::defaultCorrelationId);

        final ManageConfigurationUseCase.SubmitConfigurationCommand command =
            new ManageConfigurationUseCase.SubmitConfigurationCommand(
                mintId,
                operatorId,
                configurationPayload,
                versionTag,
                reasonCodes,
                ticketReferences,
                checklist,
                requestId,
                correlationId
            );
        return configurationUseCase().submit(command);
    }
}
