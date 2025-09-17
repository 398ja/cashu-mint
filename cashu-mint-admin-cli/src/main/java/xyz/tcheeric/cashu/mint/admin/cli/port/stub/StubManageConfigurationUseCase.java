package xyz.tcheeric.cashu.mint.admin.cli.port.stub;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stub implementation returning synthetic configuration workflow responses for CLI demonstrations.
 */
public class StubManageConfigurationUseCase implements ManageConfigurationUseCase {

    private final AtomicInteger revisionCounter = new AtomicInteger(1);

    @Override
    public ConfigurationWorkflowResponse submit(final SubmitConfigurationCommand command) {
        return respond("SUBMIT", command, command.payload(), ConfigurationRevisionState.APPROVED, NextAction.APPLY);
    }

    @Override
    public ConfigurationWorkflowResponse preview(final PreviewConfigurationCommand command) {
        return respond("PREVIEW", command, null, ConfigurationRevisionState.VALIDATED, NextAction.APPLY);
    }

    @Override
    public ConfigurationWorkflowResponse review(final ReviewConfigurationCommand command) {
        return respond("REVIEW", command, null, ConfigurationRevisionState.APPROVED, NextAction.APPLY);
    }

    @Override
    public ConfigurationWorkflowResponse apply(final ApplyConfigurationCommand command) {
        return respond("APPLY", command, null, ConfigurationRevisionState.APPLIED, NextAction.NONE);
    }

    @Override
    public ConfigurationWorkflowResponse rollback(final RollbackConfigurationCommand command) {
        return respond("ROLLBACK", command, null, ConfigurationRevisionState.ROLLED_BACK, NextAction.NONE);
    }

    private ConfigurationWorkflowResponse respond(final String action,
                                                   final WorkflowCommand command,
                                                   final ConfigurationPayload suppliedPayload,
                                                   final ConfigurationRevisionState state,
                                                   final NextAction nextAction) {
        final Instant now = Instant.now();
        final String mintId = normalise(command.mintId(), ConfigurationWorkflowCliDefaults.DEFAULT_MINT_ID);
        final String requestedRevision = "rev-" + revisionCounter.getAndIncrement();
        final ConfigurationPayload payload = suppliedPayload != null ? suppliedPayload :
            new ConfigurationPayload(Map.of(), Map.of(), action + " configuration");

        final Map<String, ConfigurationValueDto> requestedValues = toValueDtos(payload);
        final ConfigurationSnapshot requestedSnapshot = new ConfigurationSnapshot(
            requestedRevision,
            requestedValues,
            now,
            command.versionTag()
        );
        final ConfigurationSnapshot activeSnapshot = new ConfigurationSnapshot(
            "rev-active",
            Map.of(
                "currency", new ConfigurationValueDto("sat", false, null),
                "mintName", new ConfigurationValueDto("Cashu Demo", false, null)
            ),
            now.minusSeconds(3_600),
            "v-current"
        );

        final DiffSummary diffSummary = new DiffSummary(requestedValues.size(), 0, 0);
        final DiffArtefact diff = new DiffArtefact(requestedValues, Map.of(), Map.of());
        final ValidationSummary validation = new ValidationSummary(true, true, List.of(), Map.of(), "stub-validator", now);
        final ReviewDecision decision = ReviewDecision.APPROVE;
        final ApprovalSummary approval = new ApprovalSummary(true, command.operatorId(), now, List.of(), decision);
        final ApprovalChecklist checklist = new ApprovalChecklist(List.of("security-review"), List.of(), List.of());
        final List<ApprovalRecordSummary> approvalHistory = List.of(
            new ApprovalRecordSummary("auditor", now.minusSeconds(7_200), List.of(),
                command.requestId(), command.correlationId(), decision)
        );
        final AuditReference auditReference = new AuditReference(command.requestId(), command.correlationId());
        final AutomationDescriptor automation = new AutomationDescriptor(false, "cli-stub", null);
        final AuditSummary audit = new AuditSummary(
            command.operatorId(),
            action,
            now,
            safeList(command.reasonCodes()),
            safeList(command.ticketReferences()),
            auditReference,
            automation
        );
        final List<HistoryCheckpoint> history = List.of(new HistoryCheckpoint(state, audit));
        final RollbackMetadata rollbackMetadata = state == ConfigurationRevisionState.ROLLED_BACK
            ? new RollbackMetadata("rev-active", requestedRevision, now, "Manual rollback",
                auditReference, "rollback-ticket")
            : null;

        return new ConfigurationWorkflowResponse(
            mintId,
            requestedRevision,
            activeSnapshot.revisionId(),
            command.versionTag(),
            state,
            requestedSnapshot,
            activeSnapshot,
            diffSummary,
            diff,
            validation,
            approval,
            checklist,
            approvalHistory,
            audit,
            history,
            rollbackMetadata,
            nextAction
        );
    }

    private Map<String, ConfigurationValueDto> toValueDtos(final ConfigurationPayload payload) {
        if (payload == null || payload.parameters().isEmpty()) {
            return Map.of();
        }
        final Map<String, ConfigurationValueDto> values = new LinkedHashMap<>();
        payload.parameters().forEach((key, input) -> {
            if (input == null) {
                return;
            }
            final boolean secret = input.secretReference() != null || input.secretPlaintext() != null;
            final String displayValue = input.value() != null ? input.value() :
                (input.secretPlaintext() != null ? "***" : "");
            values.put(key, new ConfigurationValueDto(displayValue, secret, input.secretReference()));
        });
        return values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    private List<String> safeList(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values);
    }

    private String normalise(final String value, final String fallback) {
        if (value == null) {
            return fallback;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /**
     * Exposes shared constants used by the CLI defaults.
     */
    private static final class ConfigurationWorkflowCliDefaults {
        private static final String DEFAULT_MINT_ID = "default-mint";

        private ConfigurationWorkflowCliDefaults() {
        }
    }
}
