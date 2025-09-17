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

    ManageConfigurationResponse handle(ManageConfigurationRequest request);

    /**
     * Supported commands for configuration lifecycle management.
     */
    enum ConfigurationCommand {
        SUBMIT,
        DIFF,
        VALIDATE,
        APPROVE,
        APPLY,
        ROLLBACK
    }

    /**
     * Input payload describing the configuration request.
     */
    record ManageConfigurationRequest(String mintId,
                                      String operatorId,
                                      String targetRevision,
                                      ConfigurationCommand command,
                                      String versionTag,
                                      Map<String, ConfigurationValueInput> parameters,
                                      List<String> reasonCodes,
                                      List<String> ticketReferences,
                                      List<String> approvalConditions,
                                      String requestId,
                                      String correlationId) { }

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
    record ManageConfigurationResponse(String mintId,
                                       String requestedRevision,
                                       String activeRevision,
                                       String versionTag,
                                       ConfigurationRevisionState state,
                                       DiffArtefact diff,
                                       ValidationSummary validation,
                                       ApprovalSummary approval,
                                       List<ApprovalRecordSummary> approvalHistory,
                                       AuditSummary audit,
                                       List<HistoryCheckpoint> history,
                                       NextAction nextAction) { }

    /**
     * Represents a single entry in the computed diff between configuration revisions.
     */
    record DiffArtefact(Map<String, ConfigurationValueDto> added,
                        Map<String, ParameterChangeDto> changed,
                        Map<String, ConfigurationValueDto> removed) { }

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
                           List<String> conditions) { }

    /**
     * Captures previously recorded approvals for the revision.
     */
    record ApprovalRecordSummary(String approver,
                                 Instant approvedAt,
                                 List<String> conditions,
                                 String requestId,
                                 String correlationId) { }

    /**
     * Audit metadata of the latest command execution.
     */
    record AuditSummary(String actor,
                        String action,
                        Instant timestamp,
                        List<String> reasonCodes,
                        List<String> ticketReferences,
                        String requestId,
                        String correlationId) { }

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
