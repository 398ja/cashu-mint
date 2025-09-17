package xyz.tcheeric.cashu.mint.admin.domain;

import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable representation of configuration parameters for a mint.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationSet {

    ConfigurationRevisionId revisionId;
    Map<String, ConfigurationValue> parameters;
    AuditMetadata auditMetadata;
    ConfigurationRevisionState state;
    ConfigurationRevisionHistory history;
    ValidationReport validationReport;
    ApprovalRecord approvalRecord;

    public ConfigurationSet(final ConfigurationRevisionId revisionId,
                            final Map<String, String> parameters,
                            final AuditMetadata auditMetadata) {
        this(revisionId, toConfigurationValues(parameters), auditMetadata, ConfigurationRevisionState.DRAFT,
            ConfigurationRevisionHistory.initial(ConfigurationRevisionState.DRAFT, auditMetadata), null, null);
    }

    public ConfigurationSet(final ConfigurationRevisionId revisionId,
                            final Map<String, ConfigurationValue> parameters,
                            final AuditMetadata auditMetadata,
                            final ConfigurationRevisionState state,
                            final ConfigurationRevisionHistory history,
                            final ValidationReport validationReport,
                            final ApprovalRecord approvalRecord) {
        this.revisionId = requireNonNull(revisionId, "revision id must not be null");
        this.parameters = Map.copyOf(validateParameters(parameters));
        this.auditMetadata = requireNonNull(auditMetadata, "audit metadata must not be null");
        this.state = requireNonNull(state, "state must not be null");
        this.history = requireNonNull(history, "history must not be null");
        if (history.latestState() != state) {
            throw new IllegalArgumentException("history latest state must match current state");
        }
        if (!history.latestMetadata().equals(auditMetadata)) {
            throw new IllegalArgumentException("history latest metadata must match audit metadata");
        }
        final ValidationReport report = validationReport;
        if (report != null && !report.revisionId().equals(revisionId)) {
            throw new IllegalArgumentException("validation report revision must match configuration revision");
        }
        final ApprovalRecord approval = approvalRecord;
        if (approval != null && !approval.revisionId().equals(revisionId)) {
            throw new IllegalArgumentException("approval record revision must match configuration revision");
        }
        final LifecycleContext context = auditMetadata.lifecycleContext();
        if (context.hasConfigurationRevision()
            && !context.configurationRevisionId().equals(revisionId)) {
            throw new IllegalArgumentException("audit lifecycle context must reference this configuration revision");
        }
        this.validationReport = report;
        this.approvalRecord = approval;
    }

    public ConfigurationSet updateParameter(final String key,
                                            final String value,
                                            final ConfigurationRevisionId nextRevision,
                                            final AuditMetadata metadata) {
        return updateParameter(key, ConfigurationValue.ofPlainText(value), nextRevision, metadata);
    }

    public ConfigurationSet updateParameter(final String key,
                                            final ConfigurationValue value,
                                            final ConfigurationRevisionId nextRevision,
                                            final AuditMetadata metadata) {
        final ConfigurationRevisionId revision = requireNonNull(nextRevision, "next revision must not be null");
        if (!revision.isAfter(this.revisionId)) {
            throw new IllegalArgumentException("next revision must be greater than current revision");
        }
        final Map<String, ConfigurationValue> updated = new LinkedHashMap<>(parameters);
        updated.put(requireNonBlank(key, "parameter key"), requireNonNull(value, "parameter value must not be null"));
        final AuditMetadata audit = requireNonNull(metadata, "audit metadata must not be null");
        final ConfigurationRevisionHistory newHistory = ConfigurationRevisionHistory
            .initial(ConfigurationRevisionState.DRAFT, audit);
        return new ConfigurationSet(revision, updated, audit, ConfigurationRevisionState.DRAFT, newHistory, null, null);
    }

    public ConfigurationDiff diff(final ConfigurationSet other) {
        return ConfigurationDiff.between(requireNonNull(other, "other configuration must not be null"), this);
    }

    public ConfigurationSubmittedEvent submissionEvent(final ConfigurationSet previous) {
        final ConfigurationRevisionId previousRevision = previous == null ? null : previous.revisionId();
        final ConfigurationDiff diff = previous == null
            ? ConfigurationDiff.empty()
            : ConfigurationDiff.between(previous, this);
        return new ConfigurationSubmittedEvent(revisionId, previousRevision, auditMetadata, diff);
    }

    public ConfigurationSetTransition recordValidation(final ValidationReport report) {
        final ValidationReport validation = requireNonNull(report, "validation report must not be null");
        if (!validation.revisionId().equals(revisionId)) {
            throw new IllegalArgumentException("validation report revision must match configuration revision");
        }
        final ConfigurationRevisionState nextState = validation.valid()
            ? ConfigurationRevisionState.VALIDATED
            : ConfigurationRevisionState.REJECTED;
        final AuditMetadata metadata = validation.validator();
        final ConfigurationRevisionHistory updatedHistory = history.append(nextState, metadata);
        final ConfigurationSet updated = new ConfigurationSet(revisionId, parameters, metadata, nextState, updatedHistory,
            validation, approvalRecord);
        final ConfigurationLifecycleEvent event = validation.valid()
            ? new ConfigurationValidatedEvent(revisionId, metadata, validation)
            : new ConfigurationValidationFailedEvent(revisionId, metadata, validation);
        return new ConfigurationSetTransition(updated, event);
    }

    public ConfigurationSetTransition approve(final ApprovalRecord record) {
        final ApprovalRecord approval = requireNonNull(record, "approval record must not be null");
        if (!approval.revisionId().equals(revisionId)) {
            throw new IllegalArgumentException("approval record revision must match configuration revision");
        }
        if (state != ConfigurationRevisionState.VALIDATED) {
            throw new IllegalStateException("configuration must be validated before approval");
        }
        final AuditMetadata metadata = approval.approver();
        final ConfigurationRevisionHistory updatedHistory = history.append(ConfigurationRevisionState.APPROVED, metadata);
        final ConfigurationSet updated = new ConfigurationSet(revisionId, parameters, metadata,
            ConfigurationRevisionState.APPROVED, updatedHistory, validationReport, approval);
        final ConfigurationLifecycleEvent event = new ConfigurationApprovedEvent(revisionId, metadata, approval);
        return new ConfigurationSetTransition(updated, event);
    }

    public ConfigurationSetTransition apply(final ConfigurationSet currentActive, final AuditMetadata metadata) {
        requireNonNull(currentActive, "current active configuration must not be null");
        if (state != ConfigurationRevisionState.APPROVED && state != ConfigurationRevisionState.APPLIED) {
            throw new IllegalStateException("configuration must be approved before application");
        }
        final ConfigurationDiff diff = ensureCanApplyOver(currentActive);
        final AuditMetadata audit = requireNonNull(metadata, "audit metadata must not be null");
        final ConfigurationRevisionHistory updatedHistory = history.append(ConfigurationRevisionState.APPLIED, audit);
        final ConfigurationSet updated = new ConfigurationSet(revisionId, parameters, audit,
            ConfigurationRevisionState.APPLIED, updatedHistory, validationReport, approvalRecord);
        final ConfigurationLifecycleEvent event = new ConfigurationAppliedEvent(revisionId, audit, diff);
        return new ConfigurationSetTransition(updated, event);
    }

    public ConfigurationSetTransition rollbackFrom(final ConfigurationSet active, final AuditMetadata metadata) {
        requireNonNull(active, "active configuration must not be null");
        final ConfigurationDiff diff = ensureRollbackCompatibility(active);
        final AuditMetadata audit = requireNonNull(metadata, "audit metadata must not be null");
        final ConfigurationRevisionHistory updatedHistory = history.append(ConfigurationRevisionState.ROLLED_BACK, audit);
        final ConfigurationSet updated = new ConfigurationSet(revisionId, parameters, audit,
            ConfigurationRevisionState.ROLLED_BACK, updatedHistory, validationReport, approvalRecord);
        final ConfigurationLifecycleEvent event =
            new ConfigurationRollbackEvent(active.revisionId(), revisionId, audit, diff);
        return new ConfigurationSetTransition(updated, event);
    }

    public ConfigurationDiff ensureCanApplyOver(final ConfigurationSet currentActive) {
        final ConfigurationDiff diff = ConfigurationDiff.between(currentActive, this);
        if (!revisionId.isAfter(currentActive.revisionId())) {
            throw new IllegalStateException("configuration revision must advance when applying");
        }
        if (!diff.removed().isEmpty()) {
            throw new IllegalStateException("applying configuration may not remove parameters");
        }
        enforceSecretGuardrails(diff);
        return diff;
    }

    private ConfigurationDiff ensureRollbackCompatibility(final ConfigurationSet active) {
        if (!active.revisionId().isAfter(revisionId)) {
            throw new IllegalStateException("active revision must be newer than rollback target");
        }
        if (!history.hasState(ConfigurationRevisionState.APPLIED)) {
            throw new IllegalStateException("cannot rollback to a revision that was never applied");
        }
        final ConfigurationDiff diff = ConfigurationDiff.between(active, this);
        enforceSecretGuardrails(diff);
        return diff;
    }

    private void enforceSecretGuardrails(final ConfigurationDiff diff) {
        diff.changed().forEach((key, change) -> {
            if (change.previousValue().isSecret() && !change.nextValue().isSecret()) {
                throw new IllegalStateException(
                    "cannot replace secret parameter with plain-text value for key: " + key);
            }
        });
    }

    private Map<String, ConfigurationValue> validateParameters(final Map<String, ConfigurationValue> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        final Map<String, ConfigurationValue> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, ConfigurationValue> entry : parameters.entrySet()) {
            final String key = requireNonBlank(entry.getKey(), "parameter key");
            final ConfigurationValue value = requireNonNull(entry.getValue(), "parameter value must not be null");
            copy.put(key, value);
        }
        return copy;
    }

    private static Map<String, ConfigurationValue> toConfigurationValues(final Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        final Map<String, ConfigurationValue> converted = new LinkedHashMap<>();
        parameters.forEach((key, value) ->
            converted.put(requireNonBlank(key, "parameter key"), ConfigurationValue.ofPlainText(value)));
        return converted;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
