package xyz.tcheeric.cashu.mint.proto.application.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.ConfigurationRevision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.RevisionStatus;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApprovalStateSnapshot;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApplyRevisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.AuditReference;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationRevisionDraft;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationSubmissionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.Decision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.DiffArtifact;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.RollbackRevisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.StageApproval;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationSummary;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.MissingApprovalException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.ValidationFailedException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ApprovalPolicyEngine;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.AuditTrailWriter;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ConfigurationRevisionRepository;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ConfigurationSecretsGateway;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.DiffRenderer;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.DomainEventPublisher;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.NotificationGateway;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.SchemaValidator;

@ExtendWith(MockitoExtension.class)
class DefaultManageConfigurationInteractorTest {

    @Mock
    private ConfigurationRevisionRepository revisionRepository;
    @Mock
    private SchemaValidator schemaValidator;
    @Mock
    private ApprovalPolicyEngine approvalPolicyEngine;
    @Mock
    private DiffRenderer diffRenderer;
    @Mock
    private ConfigurationSecretsGateway secretsGateway;
    @Mock
    private NotificationGateway notificationGateway;
    @Mock
    private AuditTrailWriter auditTrailWriter;
    @Mock
    private DomainEventPublisher domainEventPublisher;

    private DefaultManageConfigurationInteractor interactor;

    @BeforeEach
    void setUp() {
        interactor = new DefaultManageConfigurationInteractor(
                revisionRepository,
                schemaValidator,
                approvalPolicyEngine,
                diffRenderer,
                secretsGateway,
                notificationGateway,
                auditTrailWriter,
                domainEventPublisher);
    }

    // Verifies that a happy path submission validates, persists, and publishes events.
    @Test
    void shouldSubmitRevisionAndInitializeApprovals() {
        UUID revisionId = UUID.randomUUID();
        ValidationSummary validation = new ValidationSummary(true, List.of(), List.of(), "ok");
        DiffArtifact diff = new DiffArtifact("base", "target", "summary", List.of("resource"), Map.of());
        ConfigurationRevisionDraft draft = new ConfigurationRevisionDraft("prod", "alice", Map.of("k", "v"), "summary", "reason", false);
        ConfigurationRevision initial = new ConfigurationRevision(
                revisionId,
                draft.scope(),
                UUID.randomUUID(),
                RevisionStatus.DRAFT,
                draft.includesSecrets(),
                diff,
                validation,
                new ApprovalStateSnapshot(Map.of(), false, Optional.empty()),
                Optional.empty(),
                Instant.now(),
                Instant.now());
        StageApproval pendingStage = new StageApproval("security", Decision.PENDING, List.of("bob"), null, null, "");
        ApprovalStateSnapshot snapshot = new ApprovalStateSnapshot(Map.of("security", pendingStage), false, Optional.of("security"));
        AuditReference auditReference = new AuditReference("ref", Instant.now(), "alice");

        when(schemaValidator.validate(draft)).thenReturn(validation);
        when(diffRenderer.renderDiff(draft, Optional.empty())).thenReturn(diff);
        when(revisionRepository.saveDraft(draft, validation, diff)).thenReturn(initial);
        when(revisionRepository.updateApprovalState(revisionId, snapshot))
                .thenReturn(initial.withApprovalState(snapshot));
        when(approvalPolicyEngine.initializeApprovals(any(ConfigurationRevision.class))).thenReturn(snapshot);
        when(revisionRepository.save(any(ConfigurationRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(auditTrailWriter.record(any())).thenReturn(auditReference);
        doNothing().when(notificationGateway).notify(any());
        doNothing().when(domainEventPublisher).publish(any());

        ConfigurationSubmissionRequest request = new ConfigurationSubmissionRequest(draft, false);

        var response = interactor.submitRevision(request);

        assertThat(response.revisionId()).isEqualTo(revisionId);
        assertThat(response.validation()).isEqualTo(validation);
        assertThat(response.approvalState().nextPendingStage()).contains("security");
        assertThat(response.auditReference()).isEqualTo(auditReference);
        verify(schemaValidator).validate(draft);
        verify(diffRenderer).renderDiff(draft, Optional.empty());
        verify(notificationGateway).notify(any());
        verify(domainEventPublisher).publish(any());
    }

    // Ensures that invalid revisions trigger a validation exception before persistence.
    @Test
    void shouldRejectInvalidSubmission() {
        ConfigurationRevisionDraft draft = new ConfigurationRevisionDraft("prod", "alice", Map.of(), "summary", "reason", false);
        ValidationSummary invalid = new ValidationSummary(false, List.of("error"), List.of(), "nope");
        when(schemaValidator.validate(draft)).thenReturn(invalid);

        assertThatThrownBy(() -> interactor.submitRevision(new ConfigurationSubmissionRequest(draft, false)))
                .isInstanceOf(ValidationFailedException.class);
    }

    // Confirms that apply rejects revisions that are not fully approved.
    @Test
    void shouldNotApplyWhenApprovalsMissing() {
        UUID revisionId = UUID.randomUUID();
        DiffArtifact diff = new DiffArtifact("base", "target", "summary", List.of(), Map.of());
        ValidationSummary validation = new ValidationSummary(true, List.of(), List.of(), "ok");
        ApprovalStateSnapshot snapshot = new ApprovalStateSnapshot(Map.of(), false, Optional.of("security"));
        ConfigurationRevision revision = new ConfigurationRevision(
                revisionId,
                "prod",
                UUID.randomUUID(),
                RevisionStatus.APPROVAL_PENDING,
                false,
                diff,
                validation,
                snapshot,
                Optional.empty(),
                Instant.now(),
                Instant.now());
        when(revisionRepository.findById(revisionId)).thenReturn(Optional.of(revision));

        assertThatThrownBy(() -> interactor.applyApprovedRevision(new ApplyRevisionRequest(revisionId, false, "alice")))
                .isInstanceOf(MissingApprovalException.class);
    }

    // Validates that rollback coordinates repository, audit, and notifications.
    @Test
    void shouldRollbackAppliedRevision() {
        UUID appliedId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        DiffArtifact diff = new DiffArtifact("base", "target", "summary", List.of(), Map.of());
        ValidationSummary validation = new ValidationSummary(true, List.of(), List.of(), "ok");
        ApprovalStateSnapshot snapshot = new ApprovalStateSnapshot(Map.of(), true, Optional.empty());
        ConfigurationRevision applied = new ConfigurationRevision(
                appliedId,
                "prod",
                UUID.randomUUID(),
                RevisionStatus.APPLIED,
                false,
                diff,
                validation,
                snapshot,
                Optional.of(Instant.now()),
                Instant.now(),
                Instant.now());
        ConfigurationRevision target = new ConfigurationRevision(
                targetId,
                "prod",
                UUID.randomUUID(),
                RevisionStatus.APPROVED,
                false,
                diff,
                validation,
                snapshot,
                Optional.empty(),
                Instant.now(),
                Instant.now());
        AuditReference auditReference = new AuditReference("ref", Instant.now(), "bob");

        when(revisionRepository.findById(appliedId)).thenReturn(Optional.of(applied));
        when(revisionRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(revisionRepository.markRolledBack(eq(appliedId), eq(targetId), any(Instant.class)))
                .thenReturn(target);
        when(auditTrailWriter.record(any())).thenReturn(auditReference);
        doNothing().when(notificationGateway).notify(any());
        doNothing().when(domainEventPublisher).publish(any());

        RollbackRevisionRequest request = new RollbackRevisionRequest(appliedId, targetId, "bob", "issue");

        var response = interactor.rollbackRevision(request);

        assertThat(response.appliedRevisionId()).isEqualTo(appliedId);
        assertThat(response.restoredRevisionId()).isEqualTo(targetId);
        assertThat(response.auditReference()).isEqualTo(auditReference);
        verify(revisionRepository).markRolledBack(eq(appliedId), eq(targetId), any(Instant.class));
        verify(notificationGateway).notify(any());
        verify(domainEventPublisher).publish(any());
    }
}
