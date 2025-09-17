package xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApprovalChecklist;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApprovalRecordSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApprovalSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.AuditSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationSnapshot;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationValueDto;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationWorkflowResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.DiffArtefact;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.DiffSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.HistoryCheckpoint;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ParameterChangeDto;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.RollbackMetadata;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ValidationSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds CLI-facing views of configuration workflow responses with secret redaction applied.
 */
final class ConfigurationWorkflowViewFactory {

    ConfigurationWorkflowView build(final ConfigurationWorkflowResponse response) {
        Objects.requireNonNull(response, "response");
        final SummaryView summary = new SummaryView(
            response.mintId(),
            response.requestedRevision(),
            response.activeRevision(),
            response.state() != null ? response.state().name() : null,
            response.versionTag(),
            resolveNextAction(response.nextAction())
        );
        final SnapshotView requested = buildSnapshot(response.requestedConfiguration());
        final SnapshotView active = buildSnapshot(response.activeConfiguration());
        final DiffView diff = buildDiff(response.diffSummary(), response.diff());
        final ValidationView validation = buildValidation(response.validation());
        final ApprovalView approval = buildApproval(response.approval(), response.approvalChecklist(),
            response.approvalHistory());
        final AuditView audit = buildAudit(response.audit());
        final RollbackView rollback = buildRollback(response.rollback());
        final List<HistoryEntryView> history = buildHistory(response.history());
        return new ConfigurationWorkflowView(summary, requested, active, diff, validation, approval, audit, rollback, history);
    }

    private String resolveNextAction(final ManageConfigurationUseCase.NextAction nextAction) {
        return nextAction == null ? ManageConfigurationUseCase.NextAction.NONE.name() : nextAction.name();
    }

    private SnapshotView buildSnapshot(final ConfigurationSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        final List<ParameterValueView> values = toParameterValues(snapshot.values());
        return new SnapshotView(snapshot.revisionId(), formatInstant(snapshot.capturedAt()), snapshot.versionTag(), values);
    }

    private DiffView buildDiff(final DiffSummary summary, final DiffArtefact artefact) {
        if (summary == null && artefact == null) {
            return null;
        }
        final DiffSummaryView summaryView = summary == null
            ? new DiffSummaryView(0, 0, 0)
            : new DiffSummaryView(summary.added(), summary.changed(), summary.removed());
        final List<DiffEntryView> added = artefact != null ? toDiffEntries(artefact.added()) : List.of();
        final List<DiffChangeView> changed = artefact != null ? toDiffChanges(artefact.changed()) : List.of();
        final List<DiffEntryView> removed = artefact != null ? toDiffEntries(artefact.removed()) : List.of();
        return new DiffView(summaryView, added, changed, removed);
    }

    private ValidationView buildValidation(final ValidationSummary validation) {
        if (validation == null) {
            return null;
        }
        return new ValidationView(
            validation.executed(),
            validation.valid(),
            validation.issues() == null ? List.of() : List.copyOf(validation.issues()),
            validation.artefacts() == null ? Map.of() : Map.copyOf(validation.artefacts()),
            validation.validator(),
            formatInstant(validation.validatedAt())
        );
    }

    private ApprovalView buildApproval(final ApprovalSummary approval,
                                       final ApprovalChecklist checklist,
                                       final List<ApprovalRecordSummary> history) {
        if (approval == null && checklist == null && (history == null || history.isEmpty())) {
            return null;
        }
        final ChecklistView checklistView = checklist == null ? null
            : new ChecklistView(
                safeList(checklist.required()),
                safeList(checklist.satisfied()),
                safeList(checklist.outstanding())
            );
        final List<ApprovalRecordView> historyView = history == null
            ? List.of()
            : history.stream()
                .map(record -> new ApprovalRecordView(
                    record.approver(),
                    formatInstant(record.approvedAt()),
                    safeList(record.conditions()),
                    record.requestId(),
                    record.correlationId(),
                    record.decision() != null ? record.decision().name() : null
                ))
                .collect(Collectors.toUnmodifiableList());
        if (approval == null) {
            return new ApprovalView(false, null, null, List.of(), null, checklistView, historyView);
        }
        return new ApprovalView(
            approval.approved(),
            approval.approver(),
            formatInstant(approval.approvedAt()),
            safeList(approval.conditions()),
            approval.decision() != null ? approval.decision().name() : null,
            checklistView,
            historyView
        );
    }

    private AuditView buildAudit(final AuditSummary audit) {
        if (audit == null) {
            return null;
        }
        final AuditReferenceView referenceView = audit.reference() == null ? null
            : new AuditReferenceView(audit.reference().requestId(), audit.reference().correlationId());
        final AutomationView automation = audit.automation() == null ? null
            : new AutomationView(audit.automation().automated(), audit.automation().system(), audit.automation().runId());
        return new AuditView(
            audit.actor(),
            audit.action(),
            formatInstant(audit.timestamp()),
            safeList(audit.reasonCodes()),
            safeList(audit.ticketReferences()),
            referenceView,
            automation
        );
    }

    private RollbackView buildRollback(final RollbackMetadata rollback) {
        if (rollback == null) {
            return null;
        }
        final AuditReferenceView referenceView = rollback.reference() == null ? null
            : new AuditReferenceView(rollback.reference().requestId(), rollback.reference().correlationId());
        return new RollbackView(
            rollback.sourceRevision(),
            rollback.targetRevision(),
            formatInstant(rollback.executedAt()),
            rollback.reason(),
            referenceView,
            rollback.externalReference()
        );
    }

    private List<HistoryEntryView> buildHistory(final List<HistoryCheckpoint> checkpoints) {
        if (checkpoints == null || checkpoints.isEmpty()) {
            return List.of();
        }
        final List<HistoryEntryView> entries = new ArrayList<>();
        for (final HistoryCheckpoint checkpoint : checkpoints) {
            final String state = checkpoint.state() == null ? null : checkpoint.state().name();
            final AuditView audit = buildAudit(checkpoint.audit());
            entries.add(new HistoryEntryView(state, audit));
        }
        return List.copyOf(entries);
    }

    private List<ParameterValueView> toParameterValues(final Map<String, ConfigurationValueDto> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .map(entry -> new ParameterValueView(entry.getKey(), sanitizeValue(entry.getValue())))
            .collect(Collectors.toUnmodifiableList());
    }

    private List<DiffEntryView> toDiffEntries(final Map<String, ConfigurationValueDto> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .map(entry -> new DiffEntryView(entry.getKey(), sanitizeValue(entry.getValue())))
            .collect(Collectors.toUnmodifiableList());
    }

    private List<DiffChangeView> toDiffChanges(final Map<String, ParameterChangeDto> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .map(entry -> {
                final ParameterChangeDto change = entry.getValue();
                return new DiffChangeView(
                    entry.getKey(),
                    sanitizeValue(change != null ? change.previousValue() : null),
                    sanitizeValue(change != null ? change.nextValue() : null)
                );
            })
            .collect(Collectors.toUnmodifiableList());
    }

    private ValueView sanitizeValue(final ConfigurationValueDto value) {
        if (value == null) {
            return new ValueView("", false, null);
        }
        final boolean secret = value.secret();
        final String reference = normalize(value.secretReference());
        final String display;
        if (secret) {
            display = "***";
        } else {
            display = normalize(value.value());
        }
        return new ValueView(display, secret, reference);
    }

    private List<String> safeList(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values);
    }

    private String formatInstant(final Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private String normalize(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record ConfigurationWorkflowView(SummaryView summary,
                                      SnapshotView requestedConfiguration,
                                      SnapshotView activeConfiguration,
                                      DiffView diff,
                                      ValidationView validation,
                                      ApprovalView approval,
                                      AuditView audit,
                                      RollbackView rollback,
                                      List<HistoryEntryView> history) { }

    record SummaryView(String mintId,
                        String requestedRevision,
                        String activeRevision,
                        String state,
                        String versionTag,
                        String nextAction) { }

    record SnapshotView(String revisionId,
                         String capturedAt,
                         String versionTag,
                         List<ParameterValueView> values) { }

    record ParameterValueView(String parameter, ValueView value) { }

    record DiffView(DiffSummaryView summary,
                     List<DiffEntryView> added,
                     List<DiffChangeView> changed,
                     List<DiffEntryView> removed) { }

    record DiffSummaryView(int added, int changed, int removed) { }

    record DiffEntryView(String parameter, ValueView value) { }

    record DiffChangeView(String parameter, ValueView previousValue, ValueView nextValue) { }

    record ValueView(String value, boolean secret, String secretReference) { }

    record ValidationView(boolean executed,
                           boolean valid,
                           List<String> issues,
                           Map<String, String> artefacts,
                           String validator,
                           String validatedAt) { }

    record ApprovalView(boolean approved,
                         String approver,
                         String approvedAt,
                         List<String> conditions,
                         String decision,
                         ChecklistView checklist,
                         List<ApprovalRecordView> history) { }

    record ChecklistView(List<String> required, List<String> satisfied, List<String> outstanding) { }

    record ApprovalRecordView(String approver,
                               String approvedAt,
                               List<String> conditions,
                               String requestId,
                               String correlationId,
                               String decision) { }

    record AuditView(String actor,
                      String action,
                      String timestamp,
                      List<String> reasonCodes,
                      List<String> ticketReferences,
                      AuditReferenceView reference,
                      AutomationView automation) { }

    record AuditReferenceView(String requestId, String correlationId) { }

    record AutomationView(boolean automated, String system, String runId) { }

    record RollbackView(String sourceRevision,
                         String targetRevision,
                         String executedAt,
                         String reason,
                         AuditReferenceView reference,
                         String externalReference) { }

    record HistoryEntryView(String state, AuditView audit) { }
}
