package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigurationSetTest {

    private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");

    private static AuditMetadata metadata(final String action) {
        return new AuditMetadata("alice", action, NOW);
    }

    private static ConfigurationSet configuration(final long revision, final Map<String, String> parameters) {
        return new ConfigurationSet(ConfigurationRevisionId.of(revision), parameters, metadata("config-" + revision));
    }

    private static ValidationReport successfulValidation(final ConfigurationSet configurationSet) {
        return ValidationReport.success(configurationSet.revisionId(),
            new AuditMetadata("validator", "validate", NOW.plusSeconds(5)), NOW.plusSeconds(5), Map.of());
    }

    private static ValidationReport failedValidation(final ConfigurationSet configurationSet) {
        return ValidationReport.failure(configurationSet.revisionId(), List.of("invalid"),
            new AuditMetadata("validator", "validate", NOW.plusSeconds(5)), NOW.plusSeconds(5), Map.of());
    }

    private static ApprovalRecord approvalRecord(final ConfigurationSet configurationSet) {
        return new ApprovalRecord(configurationSet.revisionId(),
            new AuditMetadata("approver", "approve", NOW.plusSeconds(10)), NOW.plusSeconds(10), List.of("notify"), null);
    }

    private static ConfigurationSet validated(final ConfigurationSet configurationSet) {
        return configurationSet.recordValidation(successfulValidation(configurationSet)).configurationSet();
    }

    private static ConfigurationSet approved(final ConfigurationSet configurationSet) {
        final ConfigurationSet validated = validated(configurationSet);
        return validated.approve(approvalRecord(validated)).configurationSet();
    }

    @Test
    // Ensures constructor copies values into immutable configuration value wrappers.
    void shouldCreateConfigurationSetWithValidatedInputs() {
        final ConfigurationSet configurationSet = configuration(1, Map.of("key", "value"));

        assertThat(configurationSet.parameters()).containsEntry("key", ConfigurationValue.ofPlainText("value"));
        assertThat(configurationSet.parameters()).isUnmodifiable();
        assertThat(configurationSet.state()).isEqualTo(ConfigurationRevisionState.DRAFT);
        assertThat(configurationSet.history().latestState()).isEqualTo(ConfigurationRevisionState.DRAFT);
    }

    @Test
    // Ensures blank keys are rejected when constructing the configuration set.
    void shouldThrowWhenParameterKeyBlank() {
        assertThrows(IllegalArgumentException.class, () -> configuration(1, Map.of(" ", "value")));
    }

    @Test
    // Ensures blank values are rejected when constructing the configuration set.
    void shouldThrowWhenParameterValueBlank() {
        assertThrows(IllegalArgumentException.class, () -> configuration(1, Map.of("key", "")));
    }

    @Test
    // Ensures updateParameter enforces monotonically increasing revisions.
    void shouldThrowWhenNextRevisionNotGreater() {
        final ConfigurationSet configurationSet = configuration(2, Map.of("key", "value"));

        assertThrows(IllegalArgumentException.class,
            () -> configurationSet.updateParameter("key", "new", ConfigurationRevisionId.of(2), metadata("no-op")));
    }

    @Test
    // Ensures updateParameter returns a new configuration with updated values and revision.
    void shouldUpdateParameterAndAdvanceRevision() {
        final ConfigurationSet configurationSet = configuration(1, Map.of("key", "value"));
        final ConfigurationRevisionId nextRevision = ConfigurationRevisionId.of(2);

        final ConfigurationSet updated =
            configurationSet.updateParameter("key", "new", nextRevision, metadata("update"));

        assertThat(updated.parameters()).containsEntry("key", ConfigurationValue.ofPlainText("new"));
        assertThat(updated.revisionId()).isEqualTo(nextRevision);
        assertThat(updated.history().latestState()).isEqualTo(ConfigurationRevisionState.DRAFT);
        assertThat(configurationSet.parameters()).containsEntry("key", ConfigurationValue.ofPlainText("value"));
    }

    @Test
    // Ensures diff identifies added, removed, and changed entries.
    void shouldComputeDiffBetweenRevisions() {
        final ConfigurationSet current = configuration(1, Map.of("alpha", "1", "beta", "2"));
        final ConfigurationSet next = configuration(2, Map.of("alpha", "1", "gamma", "3"));

        final ConfigurationDiff diff = next.diff(current);

        assertThat(diff.added()).containsEntry("gamma", ConfigurationValue.ofPlainText("3"));
        assertThat(diff.removed()).containsEntry("beta", ConfigurationValue.ofPlainText("2"));
        assertThat(diff.changed()).isEmpty();
    }

    @Test
    // Ensures submission events capture the prior revision and diff snapshot.
    void shouldEmitSubmissionEvent() {
        final ConfigurationSet previous = configuration(1, Map.of("alpha", "1"));
        final ConfigurationSet next = configuration(2, Map.of("alpha", "2"));

        final ConfigurationSubmittedEvent event = next.submissionEvent(previous);

        assertThat(event.revisionId()).isEqualTo(next.revisionId());
        assertThat(event.previousRevisionId()).isEqualTo(previous.revisionId());
        assertThat(event.diff().changed()).containsKey("alpha");
        assertThat(event.state()).isEqualTo(ConfigurationRevisionState.DRAFT);
    }

    @Test
    // Ensures validation reports transition the configuration to validated state.
    void shouldRecordSuccessfulValidation() {
        final ConfigurationSet configurationSet = configuration(1, Map.of("key", "value"));

        final ConfigurationSetTransition transition = configurationSet.recordValidation(successfulValidation(configurationSet));

        assertThat(transition.configurationSet().state()).isEqualTo(ConfigurationRevisionState.VALIDATED);
        assertThat(transition.lifecycleEvent()).isInstanceOf(ConfigurationValidatedEvent.class);
        assertThat(transition.configurationSet().validationReport().valid()).isTrue();
    }

    @Test
    // Ensures failed validation sets the revision into a rejected state.
    void shouldRecordFailedValidation() {
        final ConfigurationSet configurationSet = configuration(1, Map.of("key", "value"));

        final ConfigurationSetTransition transition = configurationSet.recordValidation(failedValidation(configurationSet));

        assertThat(transition.configurationSet().state()).isEqualTo(ConfigurationRevisionState.REJECTED);
        assertThat(transition.lifecycleEvent()).isInstanceOf(ConfigurationValidationFailedEvent.class);
        assertThat(transition.configurationSet().validationReport().valid()).isFalse();
    }

    @Test
    // Ensures approval requires a validated configuration.
    void shouldRejectApprovalWhenNotValidated() {
        final ConfigurationSet configurationSet = configuration(1, Map.of("key", "value"));

        assertThrows(IllegalStateException.class, () -> configurationSet.approve(approvalRecord(configurationSet)));
    }

    @Test
    // Ensures approval transitions the configuration to approved state.
    void shouldApproveAfterValidation() {
        final ConfigurationSet configurationSet = configuration(1, Map.of("key", "value"));
        final ConfigurationSet validated = validated(configurationSet);

        final ConfigurationSetTransition transition = validated.approve(approvalRecord(validated));

        assertThat(transition.configurationSet().state()).isEqualTo(ConfigurationRevisionState.APPROVED);
        assertThat(transition.lifecycleEvent()).isInstanceOf(ConfigurationApprovedEvent.class);
        assertThat(transition.configurationSet().approvalRecord()).isNotNull();
    }

    @Test
    // Ensures applying a configuration requires approval and guards against parameter removal.
    void shouldPreventApplyWhenRemovingParameters() {
        final ConfigurationSet current = configuration(1, Map.of("alpha", "1", "beta", "2"));
        final ConfigurationSet updated = configuration(2, Map.of("alpha", "1"));
        final ConfigurationSet approved = approved(updated);

        assertThrows(IllegalStateException.class, () -> approved.ensureCanApplyOver(current));
    }

    @Test
    // Ensures secrets cannot be downgraded to plain-text values during apply.
    void shouldPreventSecretDowngrade() {
        final ConfigurationSet base = configuration(1, Map.of("secret", "placeholder"));
        final ConfigurationSet secretRevision =
            base.updateParameter("secret", ConfigurationValue.ofSecret(ConfigurationSecret.namedReference("vault")),
                ConfigurationRevisionId.of(2), metadata("secret"));
        final ConfigurationSet approvedSecret = approved(secretRevision);
        final ConfigurationSet downgrade = configuration(3, Map.of("secret", "plaintext"));
        final ConfigurationSet approvedDowngrade = approved(downgrade);

        assertThrows(IllegalStateException.class, () -> approvedDowngrade.ensureCanApplyOver(approvedSecret));
    }

    @Test
    // Ensures applying an approved configuration produces an applied event.
    void shouldApplyApprovedConfiguration() {
        final ConfigurationSet current = configuration(1, Map.of("alpha", "1"));
        final ConfigurationSet updated = configuration(2, Map.of("alpha", "2", "beta", "3"));
        final ConfigurationSet approved = approved(updated);

        final ConfigurationSetTransition transition =
            approved.apply(current, new AuditMetadata("deployer", "apply", NOW.plusSeconds(15)));

        assertThat(transition.configurationSet().state()).isEqualTo(ConfigurationRevisionState.APPLIED);
        assertThat(transition.lifecycleEvent()).isInstanceOf(ConfigurationAppliedEvent.class);
        assertThat(transition.lifecycleEvent()).extracting("diff").isNotNull();
    }

    @Test
    // Ensures rollback requires the target revision to have been applied previously.
    void shouldPreventRollbackIfNeverApplied() {
        final ConfigurationSet revision = configuration(1, Map.of("alpha", "1"));
        final ConfigurationSet active = configuration(2, Map.of("alpha", "2"));

        assertThrows(IllegalStateException.class,
            () -> revision.rollbackFrom(active, new AuditMetadata("operator", "rollback", NOW.plusSeconds(20))));
    }

    @Test
    // Ensures rollback transitions applied revisions and emits a rollback event.
    void shouldRollbackToPreviouslyAppliedRevision() {
        final ConfigurationSet base = configuration(1, Map.of("alpha", "0"));
        final ConfigurationSet initial = configuration(2, Map.of("alpha", "1"));
        final ConfigurationSet appliedInitial =
            approved(initial).apply(base, new AuditMetadata("deployer", "apply", NOW.plusSeconds(15))).configurationSet();
        final ConfigurationSet active = configuration(3, Map.of("alpha", "2"));
        final ConfigurationSet approvedActive =
            approved(active).apply(appliedInitial, new AuditMetadata("deployer", "apply", NOW.plusSeconds(30)))
                .configurationSet();

        final ConfigurationSetTransition rollback =
            appliedInitial.rollbackFrom(approvedActive, new AuditMetadata("operator", "rollback", NOW.plusSeconds(40)));

        assertThat(rollback.configurationSet().state()).isEqualTo(ConfigurationRevisionState.ROLLED_BACK);
        assertThat(rollback.lifecycleEvent()).isInstanceOf(ConfigurationRollbackEvent.class);
        assertThat(((ConfigurationRollbackEvent) rollback.lifecycleEvent()).targetRevisionId())
            .isEqualTo(appliedInitial.revisionId());
    }
}
