package xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationWorkflowResponse;
import xyz.tcheeric.cashu.mint.admin.cli.io.TableResponseRenderer;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.ApprovalRecordView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.ApprovalView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.AuditView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.ChecklistView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.ConfigurationWorkflowView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.DiffChangeView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.DiffEntryView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.DiffView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.HistoryEntryView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.RollbackView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.SummaryView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.ValidationView;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowViewFactory.ValueView;

/**
 * Presents configuration workflow responses as rich, sectioned tables.
 */
final class ConfigurationWorkflowTablePresenter {

    private final TableResponseRenderer renderer;
    private final ConfigurationWorkflowViewFactory viewFactory;

    ConfigurationWorkflowTablePresenter(final ObjectMapper mapper) {
        this(new TableResponseRenderer(mapper), new ConfigurationWorkflowViewFactory());
    }

    ConfigurationWorkflowTablePresenter(final TableResponseRenderer renderer,
                                        final ConfigurationWorkflowViewFactory viewFactory) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.viewFactory = Objects.requireNonNull(viewFactory, "viewFactory");
    }

    String present(final ConfigurationWorkflowResponse response) {
        if (response == null) {
            return "";
        }
        final ConfigurationWorkflowView view = viewFactory.build(response);
        final StringBuilder builder = new StringBuilder();
        appendSection(builder, "Summary", List.of(summaryRow(view.summary())));
        if (view.diff() != null) {
            appendDiff(builder, view.diff());
        }
        if (view.validation() != null) {
            appendSection(builder, "Validation", List.of(validationRow(view.validation())));
        }
        if (view.approval() != null) {
            appendApproval(builder, view.approval());
        }
        if (view.audit() != null) {
            appendSection(builder, "Audit", List.of(auditRow(view.audit())));
        }
        if (view.rollback() != null) {
            appendSection(builder, "Rollback", List.of(rollbackRow(view.rollback())));
        }
        if (!view.history().isEmpty()) {
            appendSection(builder, "History", historyRows(view.history()));
        }
        return builder.toString();
    }

    private Map<String, String> summaryRow(final SummaryView summary) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("Mint", safe(summary.mintId()));
        row.put("Requested Revision", safe(summary.requestedRevision()));
        row.put("Active Revision", safe(summary.activeRevision()));
        row.put("State", safe(summary.state()));
        row.put("Version Tag", safe(summary.versionTag()));
        row.put("Next Action", safe(summary.nextAction()));
        return row;
    }

    private void appendDiff(final StringBuilder builder, final DiffView diff) {
        appendSection(builder, "Diff Summary", List.of(diffSummaryRow(diff)));
        if (!diff.added().isEmpty()) {
            appendSection(builder, "Diff Added", diffEntryRows(diff.added()));
        }
        if (!diff.changed().isEmpty()) {
            appendSection(builder, "Diff Changed", diffChangeRows(diff.changed()));
        }
        if (!diff.removed().isEmpty()) {
            appendSection(builder, "Diff Removed", diffEntryRows(diff.removed()));
        }
    }

    private Map<String, String> diffSummaryRow(final DiffView diff) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("Added", Integer.toString(diff.summary().added()));
        row.put("Changed", Integer.toString(diff.summary().changed()));
        row.put("Removed", Integer.toString(diff.summary().removed()));
        return row;
    }

    private List<Map<String, String>> diffEntryRows(final List<DiffEntryView> entries) {
        final List<Map<String, String>> rows = new ArrayList<>();
        for (final DiffEntryView entry : entries) {
            final Map<String, String> row = new LinkedHashMap<>();
            row.put("Parameter", safe(entry.parameter()));
            addValueColumns(row, entry.value());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, String>> diffChangeRows(final List<DiffChangeView> changes) {
        final List<Map<String, String>> rows = new ArrayList<>();
        for (final DiffChangeView change : changes) {
            final Map<String, String> row = new LinkedHashMap<>();
            row.put("Parameter", safe(change.parameter()));
            addValueColumns(row, change.previousValue(), "Previous");
            addValueColumns(row, change.nextValue(), "Next");
            rows.add(row);
        }
        return rows;
    }

    private Map<String, String> validationRow(final ValidationView validation) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("Executed", Boolean.toString(validation.executed()));
        row.put("Valid", Boolean.toString(validation.valid()));
        row.put("Issues", join(validation.issues()));
        row.put("Artefacts", joinMap(validation.artefacts()));
        row.put("Validator", safe(validation.validator()));
        row.put("Validated At", safe(validation.validatedAt()));
        return row;
    }

    private void appendApproval(final StringBuilder builder, final ApprovalView approval) {
        appendSection(builder, "Approval", List.of(approvalRow(approval)));
        if (approval.checklist() != null) {
            appendSection(builder, "Approval Checklist", List.of(checklistRow(approval.checklist())));
        }
        if (!approval.history().isEmpty()) {
            appendSection(builder, "Approval History", approvalHistoryRows(approval.history()));
        }
    }

    private Map<String, String> approvalRow(final ApprovalView approval) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("Approved", Boolean.toString(approval.approved()));
        row.put("Approver", safe(approval.approver()));
        row.put("Approved At", safe(approval.approvedAt()));
        row.put("Decision", safe(approval.decision()));
        row.put("Conditions", join(approval.conditions()));
        return row;
    }

    private Map<String, String> checklistRow(final ChecklistView checklist) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("Required", join(checklist.required()));
        row.put("Satisfied", join(checklist.satisfied()));
        row.put("Outstanding", join(checklist.outstanding()));
        return row;
    }

    private List<Map<String, String>> approvalHistoryRows(final List<ApprovalRecordView> history) {
        final List<Map<String, String>> rows = new ArrayList<>();
        for (final ApprovalRecordView record : history) {
            final Map<String, String> row = new LinkedHashMap<>();
            row.put("Approver", safe(record.approver()));
            row.put("Approved At", safe(record.approvedAt()));
            row.put("Conditions", join(record.conditions()));
            row.put("Request ID", safe(record.requestId()));
            row.put("Correlation ID", safe(record.correlationId()));
            row.put("Decision", safe(record.decision()));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, String> auditRow(final AuditView audit) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("Actor", safe(audit.actor()));
        row.put("Action", safe(audit.action()));
        row.put("Timestamp", safe(audit.timestamp()));
        row.put("Reason Codes", join(audit.reasonCodes()));
        row.put("Ticket References", join(audit.ticketReferences()));
        row.put("Request ID", audit.reference() != null ? safe(audit.reference().requestId()) : "");
        row.put("Correlation ID", audit.reference() != null ? safe(audit.reference().correlationId()) : "");
        row.put("Automated", audit.automation() != null ? Boolean.toString(audit.automation().automated()) : "");
        row.put("Automation System", audit.automation() != null ? safe(audit.automation().system()) : "");
        row.put("Automation Run", audit.automation() != null ? safe(audit.automation().runId()) : "");
        return row;
    }

    private Map<String, String> rollbackRow(final RollbackView rollback) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("Source Revision", safe(rollback.sourceRevision()));
        row.put("Target Revision", safe(rollback.targetRevision()));
        row.put("Executed At", safe(rollback.executedAt()));
        row.put("Reason", safe(rollback.reason()));
        row.put("Request ID", rollback.reference() != null ? safe(rollback.reference().requestId()) : "");
        row.put("Correlation ID", rollback.reference() != null ? safe(rollback.reference().correlationId()) : "");
        row.put("External Reference", safe(rollback.externalReference()));
        return row;
    }

    private List<Map<String, String>> historyRows(final List<HistoryEntryView> history) {
        final List<Map<String, String>> rows = new ArrayList<>();
        for (final HistoryEntryView entry : history) {
            final Map<String, String> row = new LinkedHashMap<>();
            row.put("State", safe(entry.state()));
            if (entry.audit() != null) {
                row.put("Actor", safe(entry.audit().actor()));
                row.put("Action", safe(entry.audit().action()));
                row.put("Timestamp", safe(entry.audit().timestamp()));
                row.put("Reason Codes", join(entry.audit().reasonCodes()));
                row.put("Ticket References", join(entry.audit().ticketReferences()));
            } else {
                row.put("Actor", "");
                row.put("Action", "");
                row.put("Timestamp", "");
                row.put("Reason Codes", "");
                row.put("Ticket References", "");
            }
            rows.add(row);
        }
        return rows;
    }

    private void appendSection(final StringBuilder builder, final String title, final Object model) {
        final String rendered = renderer.render(model);
        if (rendered == null || rendered.isBlank() || "(no data)".equals(rendered)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(System.lineSeparator()).append(System.lineSeparator());
        }
        builder.append(title).append(System.lineSeparator());
        builder.append(rendered);
    }

    private void addValueColumns(final Map<String, String> row, final ValueView value) {
        addValueColumns(row, value, "Value");
    }

    private void addValueColumns(final Map<String, String> row, final ValueView value, final String prefix) {
        final ValueView safeValue = value == null ? new ValueView("", false, null) : value;
        row.put(prefix, safe(safeValue.value()));
        row.put(prefix + " Secret", Boolean.toString(safeValue.secret()));
        row.put(prefix + " Reference", safe(safeValue.secretReference()));
    }

    private String join(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().filter(Objects::nonNull).collect(Collectors.joining(", "));
    }

    private String joinMap(final Map<String, String> artefacts) {
        if (artefacts == null || artefacts.isEmpty()) {
            return "";
        }
        return artefacts.entrySet().stream()
            .map(entry -> safe(entry.getKey()) + "=" + safe(entry.getValue()))
            .collect(Collectors.joining(", "));
    }

    private String safe(final String value) {
        return value == null ? "" : value;
    }
}
