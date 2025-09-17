package xyz.tcheeric.cashu.mint.admin.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSecret;

/**
 * Handles configuration workflows such as versioning, diffing, validation, approval, apply, and rollback.
 */
public interface ManageConfigurationUseCase {

    ConfigurationWorkflowResponse submit(SubmitConfigurationCommand command);

    ConfigurationWorkflowResponse preview(PreviewConfigurationCommand command);

    ConfigurationWorkflowResponse review(ReviewConfigurationCommand command);

    ConfigurationWorkflowResponse apply(ApplyConfigurationCommand command);

    ConfigurationWorkflowResponse rollback(RollbackConfigurationCommand command);

    /**
     * Marker interface implemented by all workflow commands.
     */
    interface WorkflowCommand {

        String mintId();

        String operatorId();

        String versionTag();

        List<String> reasonCodes();

        List<String> ticketReferences();

        String requestId();

        String correlationId();
    }

    /**
     * Workflow commands that operate on a specific configuration revision.
     */
    interface TargetedCommand extends WorkflowCommand {

        String targetRevision();
    }

    /**
     * Command payload describing a configuration submission.
     */
    record SubmitConfigurationCommand(String mintId,
                                      String operatorId,
                                      ConfigurationPayload payload,
                                      String versionTag,
                                      List<String> reasonCodes,
                                      List<String> ticketReferences,
                                      List<String> approvalChecklist,
                                      String requestId,
                                      String correlationId) implements WorkflowCommand { }

    /**
     * Command payload for previewing or validating a configuration revision.
     */
    record PreviewConfigurationCommand(String mintId,
                                       String operatorId,
                                       String targetRevision,
                                       boolean includeValidation,
                                       String versionTag,
                                       List<String> reasonCodes,
                                       List<String> ticketReferences,
                                       String requestId,
                                       String correlationId) implements TargetedCommand { }

    /**
     * Decision taken during configuration review.
     */
    enum ReviewDecision {
        APPROVE,
        REJECT
    }

    /**
     * Command payload describing an approval or rejection decision.
     */
    record ReviewConfigurationCommand(String mintId,
                                      String operatorId,
                                      String targetRevision,
                                      ReviewDecision decision,
                                      List<String> approvalChecklist,
                                      List<String> rejectionReasons,
                                      String versionTag,
                                      List<String> reasonCodes,
                                      List<String> ticketReferences,
                                      String requestId,
                                      String correlationId) implements TargetedCommand { }

    /**
     * Command payload describing configuration application.
     */
    record ApplyConfigurationCommand(String mintId,
                                     String operatorId,
                                     String targetRevision,
                                     String deploymentTicket,
                                     String versionTag,
                                     List<String> reasonCodes,
                                     List<String> ticketReferences,
                                     String requestId,
                                     String correlationId) implements TargetedCommand { }

    /**
     * Command payload describing a rollback to a prior configuration revision.
     */
    record RollbackConfigurationCommand(String mintId,
                                        String operatorId,
                                        String targetRevision,
                                        String rollbackReason,
                                        String auditReference,
                                        String versionTag,
                                        List<String> reasonCodes,
                                        List<String> ticketReferences,
                                        String requestId,
                                        String correlationId) implements TargetedCommand { }

    /**
     * Configuration payload supplied by callers.
     */
    record ConfigurationPayload(Map<String, ConfigurationValueInput> parameters,
                                Map<String, String> metadata,
                                String summary) {
        public ConfigurationPayload {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            summary = summary == null ? "" : summary.trim();
        }
    }

    /**
     * Input for configuration parameters. One of {@code value}, {@code secretReference}, or {@code secretPlaintext}
     * should be provided depending on whether the value should be persisted as clear text or as a secret reference.
     */
    record ConfigurationValueInput(String value,
                                   String secretReference,
                                   String secretPlaintext,
                                   ConfigurationSecret.SecretMaterialization materialization) { }

    /**
     * Rich response payload describing the outcome of a configuration command.
     */
    record ConfigurationWorkflowResponse(String mintId,
                                          String requestedRevision,
                                          String activeRevision,
                                          String versionTag,
                                          ConfigurationRevisionState state,
                                          ConfigurationSnapshot requestedConfiguration,
                                          ConfigurationSnapshot activeConfiguration,
                                          DiffSummary diffSummary,
                                          DiffArtefact diff,
                                          ValidationSummary validation,
                                          ApprovalSummary approval,
                                          ApprovalChecklist approvalChecklist,
                                          List<ApprovalRecordSummary> approvalHistory,
                                          AuditSummary audit,
                                          List<HistoryCheckpoint> history,
                                          RollbackMetadata rollback,
                                          NextAction nextAction) { }

    /**
     * Snapshot of configuration values at a given revision.
     */
    record ConfigurationSnapshot(String revisionId,
                                 Map<String, ConfigurationValueDto> values,
                                 Instant capturedAt,
                                 String versionTag) { }

    /**
     * Represents a single entry in the computed diff between configuration revisions.
     */
    record DiffArtefact(Map<String, ConfigurationValueDto> added,
                        Map<String, ParameterChangeDto> changed,
                        Map<String, ConfigurationValueDto> removed) { }

    /**
     * Summary view of diff statistics.
     */
    record DiffSummary(int added, int changed, int removed) { }

    /**
     * Describes a configuration value for presentation purposes.
     */
    record ConfigurationValueDto(String value, boolean secret, String secretReference) { }

    /**
     * Describes a parameter change between revisions.
     */
    record ParameterChangeDto(ConfigurationValueDto previousValue, ConfigurationValueDto nextValue) { }

    /**
     * Summarises validation results.
     */
    record ValidationSummary(boolean executed,
                             boolean valid,
                             List<String> issues,
                             Map<String, String> artefacts,
                             String validator,
                             Instant validatedAt) { }

    /**
     * Summarises approval state.
     */
    record ApprovalSummary(boolean approved,
                           String approver,
                           Instant approvedAt,
                           List<String> conditions,
                           ReviewDecision decision) { }

    /**
     * Provides checklist visibility for approvals.
     */
    record ApprovalChecklist(List<String> required, List<String> satisfied, List<String> outstanding) { }

    /**
     * Captures previously recorded approvals for the revision.
     */
    record ApprovalRecordSummary(String approver,
                                 Instant approvedAt,
                                 List<String> conditions,
                                 String requestId,
                                 String correlationId,
                                 ReviewDecision decision) { }

    /**
     * Audit metadata of the latest command execution.
     */
    record AuditSummary(String actor,
                        String action,
                        Instant timestamp,
                        List<String> reasonCodes,
                        List<String> ticketReferences,
                        AuditReference reference,
                        AutomationDescriptor automation) { }

    /**
     * Reference identifiers for correlating audit trails.
     */
    record AuditReference(String requestId, String correlationId) { }

    /**
     * Automation details associated with an audit action.
     */
    record AutomationDescriptor(boolean automated, String system, String runId) { }

    /**
     * Metadata produced when a rollback command executes.
     */
    record RollbackMetadata(String sourceRevision,
                            String targetRevision,
                            Instant executedAt,
                            String reason,
                            AuditReference reference,
                            String externalReference) { }

    /**
     * Historical checkpoints for the configuration revision.
     */
    record HistoryCheckpoint(ConfigurationRevisionState state, AuditSummary audit) { }

    /**
     * Hint describing the recommended next action for the caller.
     */
    enum NextAction {
        NONE,
        SUBMIT,
        DIFF,
        VALIDATE,
        APPROVE,
        APPLY,
        ROLLBACK
    }
}
