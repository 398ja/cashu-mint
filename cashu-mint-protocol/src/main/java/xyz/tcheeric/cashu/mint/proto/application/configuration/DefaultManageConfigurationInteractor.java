package xyz.tcheeric.cashu.mint.proto.application.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.ConfigurationRevision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.RevisionStatus;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApprovalStateSnapshot;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApproverDecisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApproverDecisionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApplyRevisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApplyRevisionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.AuditEntry;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.AuditReference;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationGovernanceEvent;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationSubmissionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationSubmissionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.Decision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.DiffArtifact;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.NextStepGuidance;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.NotificationPayload;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.RollbackRevisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.RollbackRevisionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.SecretsBundle;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.SecretsCommitResult;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.StagedApprovalRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.StagedApprovalResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationPreviewRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationPreviewResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationSummary;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.ApprovalPolicyException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.ConfigurationPersistenceException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.ManageConfigurationException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.MissingApprovalException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.RevisionNotFoundException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.RollbackProtectionException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.SecretsGatewayException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.error.ValidationFailedException;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ApprovalPolicyEngine;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.AuditTrailWriter;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ConfigurationRevisionRepository;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ConfigurationSecretsGateway;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.DiffRenderer;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.DomainEventPublisher;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.NotificationGateway;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.SchemaValidator;

/**
 * Default orchestration of configuration management workflows.
 */
@Service
public class DefaultManageConfigurationInteractor implements ManageConfigurationInteractor {

    private static final Logger log = LoggerFactory.getLogger(DefaultManageConfigurationInteractor.class);

    private final ConfigurationRevisionRepository revisionRepository;
    private final SchemaValidator schemaValidator;
    private final ApprovalPolicyEngine approvalPolicyEngine;
    private final DiffRenderer diffRenderer;
    private final ConfigurationSecretsGateway secretsGateway;
    private final NotificationGateway notificationGateway;
    private final AuditTrailWriter auditTrailWriter;
    private final DomainEventPublisher domainEventPublisher;

    public DefaultManageConfigurationInteractor(ConfigurationRevisionRepository revisionRepository,
                                                 SchemaValidator schemaValidator,
                                                 ApprovalPolicyEngine approvalPolicyEngine,
                                                 DiffRenderer diffRenderer,
                                                 ConfigurationSecretsGateway secretsGateway,
                                                 NotificationGateway notificationGateway,
                                                 AuditTrailWriter auditTrailWriter,
                                                 DomainEventPublisher domainEventPublisher) {
        this.revisionRepository = revisionRepository;
        this.schemaValidator = schemaValidator;
        this.approvalPolicyEngine = approvalPolicyEngine;
        this.diffRenderer = diffRenderer;
        this.secretsGateway = secretsGateway;
        this.notificationGateway = notificationGateway;
        this.auditTrailWriter = auditTrailWriter;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Override
    public ConfigurationSubmissionResponse submitRevision(ConfigurationSubmissionRequest request) {
        ValidationSummary validation = schemaValidator.validate(request.draft());
        if (!validation.valid() || validation.hasErrors()) {
            throw new ValidationFailedException("Revision payload failed schema validation");
        }

        Optional<ConfigurationRevision> baseline = revisionRepository.findLatestApplied(request.draft().scope());
        DiffArtifact diff = diffRenderer.renderDiff(request.draft(), baseline);

        ConfigurationRevision persisted = persistDraft(request, validation, diff);

        ConfigurationRevision revisionForInitialization = persisted;
        ApprovalStateSnapshot approvalSnapshot = withPolicy("initialize approvals",
                () -> approvalPolicyEngine.initializeApprovals(revisionForInitialization));
        persisted = updateApprovalState(persisted.revisionId(), approvalSnapshot);

        if (request.autoRequestApproval() && approvalSnapshot.nextPendingStage().isPresent()) {
            String stage = approvalSnapshot.nextPendingStage().get();
            ConfigurationRevision revisionForStageRequest = persisted;
            approvalSnapshot = withPolicy("request stage approval",
                    () -> approvalPolicyEngine.requestStage(revisionForStageRequest, stage, request.draft().submittedBy()));
            persisted = updateApprovalState(persisted.revisionId(), approvalSnapshot);
        }

        RevisionStatus status = approvalSnapshot.fullyApproved()
                ? RevisionStatus.APPROVED
                : RevisionStatus.APPROVAL_PENDING;
        persisted = saveSafely(persisted.withStatus(status));

        AuditReference auditReference = recordAudit(persisted.revisionId(),
                "SUBMIT_REVISION",
                request.draft().submittedBy(),
                "Revision submitted with status " + persisted.status());

        notificationGateway.notify(new NotificationPayload(
                persisted.revisionId(),
                "REVISION_SUBMITTED",
                Map.of(
                        "scope", persisted.scope(),
                        "status", persisted.status().name())));

        NextStepGuidance nextSteps = submissionGuidance(approvalSnapshot, request.autoRequestApproval());
        ConfigurationSubmissionResponse response = new ConfigurationSubmissionResponse(
                persisted.revisionId(),
                persisted.diffArtifact(),
                persisted.validationSummary(),
                approvalSnapshot,
                auditReference,
                nextSteps);
        publishEvent("REVISION_SUBMITTED", persisted.revisionId(), response);
        return response;
    }

    @Override
    public ValidationPreviewResponse previewValidation(ValidationPreviewRequest request) {
        ConfigurationRevision revision = getRevisionOrThrow(request.revisionId());
        NextStepGuidance guidance = revision.isApproved()
                ? new NextStepGuidance("Revision already approved; apply when ready", List.of("Apply revision"))
                : new NextStepGuidance("Review outstanding approvals before apply",
                        revision.approvalState().nextPendingStage()
                                .map(stage -> List.of("Secure approvals for stage " + stage))
                                .orElse(List.of("Trigger staged approvals")));
        return new ValidationPreviewResponse(
                revision.revisionId(),
                revision.diffArtifact(),
                revision.validationSummary(),
                guidance);
    }

    @Override
    public StagedApprovalResponse requestStagedApproval(StagedApprovalRequest request) {
        ConfigurationRevision revision = getRevisionOrThrow(request.revisionId());
        ConfigurationRevision revisionForStage = revision;
        ApprovalStateSnapshot snapshot = withPolicy("request stage approval",
                () -> approvalPolicyEngine.requestStage(revisionForStage, request.stage(), request.requestedBy()));
        revision = updateApprovalState(revision.revisionId(), snapshot);
        RevisionStatus status = snapshot.fullyApproved()
                ? RevisionStatus.APPROVED
                : RevisionStatus.APPROVAL_PENDING;
        revision = saveSafely(revision.withStatus(status));

        AuditReference auditReference = recordAudit(revision.revisionId(),
                "REQUEST_STAGE_APPROVAL",
                request.requestedBy(),
                "Stage %s requested".formatted(request.stage()));

        notificationGateway.notify(new NotificationPayload(
                revision.revisionId(),
                "STAGE_REQUESTED",
                Map.of("stage", request.stage(), "requestedBy", request.requestedBy())));

        NextStepGuidance nextSteps = approvalGuidance(snapshot);
        StagedApprovalResponse response = new StagedApprovalResponse(
                revision.revisionId(),
                snapshot,
                auditReference,
                nextSteps);
        publishEvent("STAGE_REQUESTED", revision.revisionId(), response);
        return response;
    }

    @Override
    public ApproverDecisionResponse recordApproverDecision(ApproverDecisionRequest request) {
        ConfigurationRevision revision = getRevisionOrThrow(request.revisionId());
        ConfigurationRevision revisionForDecision = revision;
        ApprovalStateSnapshot snapshot = withPolicy("record approval decision",
                () -> approvalPolicyEngine.recordDecision(revisionForDecision,
                        request.stage(),
                        request.approverId(),
                        request.decision(),
                        request.comment()));
        revision = updateApprovalState(revision.revisionId(), snapshot);

        RevisionStatus status;
        if (request.decision() == Decision.REJECTED) {
            status = RevisionStatus.REJECTED;
        } else if (snapshot.fullyApproved()) {
            status = RevisionStatus.APPROVED;
        } else {
            status = RevisionStatus.APPROVAL_PENDING;
        }
        revision = saveSafely(revision.withStatus(status));

        AuditReference auditReference = recordAudit(revision.revisionId(),
                "APPROVER_DECISION",
                request.approverId(),
                "Stage %s decision %s".formatted(request.stage(), request.decision()));

        notificationGateway.notify(new NotificationPayload(
                revision.revisionId(),
                "APPROVAL_DECISION",
                Map.of(
                        "stage", request.stage(),
                        "decision", request.decision().name(),
                        "approverId", request.approverId())));

        NextStepGuidance guidance = decisionGuidance(snapshot, request.decision());
        ApproverDecisionResponse response = new ApproverDecisionResponse(
                revision.revisionId(),
                snapshot,
                auditReference,
                guidance);
        publishEvent("APPROVER_DECISION", revision.revisionId(), response);
        return response;
    }

    @Override
    public ApplyRevisionResponse applyApprovedRevision(ApplyRevisionRequest request) {
        ConfigurationRevision revision = getRevisionOrThrow(request.revisionId());
        if (!revision.canApply()) {
            throw new MissingApprovalException("Revision lacks required approvals for apply");
        }

        Optional<SecretsBundle> secretsBundle = Optional.empty();
        if (request.includeSecrets() && revision.includesSecrets()) {
            secretsBundle = Optional.of(fetchSecrets(revision, request.requestedBy()));
        }

        ConfigurationRevision applied = markApplied(revision.revisionId());

        Optional<SecretsCommitResult> commitResult = secretsBundle.map(bundle -> commitSecrets(applied, bundle, request.requestedBy()));

        AuditReference auditReference = recordAudit(applied.revisionId(),
                "APPLY_REVISION",
                request.requestedBy(),
                "Revision applied with approvals satisfied");

        notificationGateway.notify(new NotificationPayload(
                applied.revisionId(),
                "REVISION_APPLIED",
                Map.of("appliedAt", applied.updatedAt().toString())));

        NextStepGuidance guidance = new NextStepGuidance(
                "Revision applied; monitor runtime systems",
                List.of("Observe metrics for scope %s".formatted(applied.scope())));
        ApplyRevisionResponse response = new ApplyRevisionResponse(
                applied.revisionId(),
                auditReference,
                guidance,
                commitResult);
        publishEvent("REVISION_APPLIED", applied.revisionId(), response);
        return response;
    }

    @Override
    public RollbackRevisionResponse rollbackRevision(RollbackRevisionRequest request) {
        ConfigurationRevision appliedRevision = getRevisionOrThrow(request.appliedRevisionId());
        if (!appliedRevision.canRollback()) {
            throw new RollbackProtectionException("Revision is not in an applied state");
        }
        getRevisionOrThrow(request.rollbackTargetRevisionId());

        ConfigurationRevision restored = markRolledBack(
                appliedRevision.revisionId(),
                request.rollbackTargetRevisionId());

        AuditReference auditReference = recordAudit(appliedRevision.revisionId(),
                "ROLLBACK_REVISION",
                request.requestedBy(),
                "Rollback requested: %s".formatted(request.reason()));

        notificationGateway.notify(new NotificationPayload(
                appliedRevision.revisionId(),
                "REVISION_ROLLED_BACK",
                Map.of(
                        "rolledBackTo", request.rollbackTargetRevisionId().toString(),
                        "requestedBy", request.requestedBy())));

        NextStepGuidance guidance = new NextStepGuidance(
                "Rollback completed; validate system state",
                List.of("Re-run validation checks", "Communicate rollback to stakeholders"));
        RollbackRevisionResponse response = new RollbackRevisionResponse(
                appliedRevision.revisionId(),
                restored.revisionId(),
                auditReference,
                guidance);
        publishEvent("REVISION_ROLLED_BACK", appliedRevision.revisionId(), response);
        return response;
    }

    private ConfigurationRevision persistDraft(ConfigurationSubmissionRequest request,
                                                ValidationSummary validation,
                                                DiffArtifact diff) {
        ConfigurationRevision revision;
        try {
            revision = revisionRepository.saveDraft(request.draft(), validation, diff);
        } catch (RuntimeException e) {
            throw new ConfigurationPersistenceException("Failed to persist configuration revision draft", e);
        }
        revision = revision.withValidationSummary(validation);
        revision = revision.withDiffArtifact(diff);
        revision = revision.withStatus(RevisionStatus.VALIDATED);
        return saveSafely(revision);
    }

    private ConfigurationRevision saveSafely(ConfigurationRevision revision) {
        try {
            return revisionRepository.save(revision);
        } catch (RuntimeException e) {
            throw new ConfigurationPersistenceException("Failed to persist revision state", e);
        }
    }

    private ConfigurationRevision updateApprovalState(UUID revisionId, ApprovalStateSnapshot snapshot) {
        try {
            return revisionRepository.updateApprovalState(revisionId, snapshot);
        } catch (RuntimeException e) {
            throw new ConfigurationPersistenceException("Failed to update approval state", e);
        }
    }

    private ConfigurationRevision markApplied(UUID revisionId) {
        try {
            return revisionRepository.markApplied(revisionId, Instant.now());
        } catch (RuntimeException e) {
            throw new ConfigurationPersistenceException("Failed to mark revision as applied", e);
        }
    }

    private ConfigurationRevision markRolledBack(UUID appliedRevisionId, UUID targetRevisionId) {
        try {
            return revisionRepository.markRolledBack(appliedRevisionId, targetRevisionId, Instant.now());
        } catch (RuntimeException e) {
            throw new ConfigurationPersistenceException("Failed to rollback revision", e);
        }
    }

    private SecretsBundle fetchSecrets(ConfigurationRevision revision, String requestedBy) {
        try {
            return secretsGateway.fetchSecrets(revision, requestedBy);
        } catch (RuntimeException e) {
            throw new SecretsGatewayException("Unable to fetch secrets for revision", e);
        }
    }

    private SecretsCommitResult commitSecrets(ConfigurationRevision revision,
                                              SecretsBundle bundle,
                                              String requestedBy) {
        try {
            return secretsGateway.commitSecrets(revision, bundle, requestedBy);
        } catch (RuntimeException e) {
            throw new SecretsGatewayException("Unable to commit secrets for revision", e);
        }
    }

    private AuditReference recordAudit(UUID revisionId, String action, String performedBy, String details) {
        try {
            return auditTrailWriter.record(new AuditEntry(
                    revisionId,
                    action,
                    performedBy,
                    Instant.now(),
                    details));
        } catch (RuntimeException e) {
            throw new ConfigurationPersistenceException("Failed to record audit trail entry", e);
        }
    }

    private void publishEvent(String type, UUID revisionId, Object payload) {
        try {
            domainEventPublisher.publish(new ConfigurationGovernanceEvent(revisionId, type, Instant.now(), payload));
        } catch (RuntimeException e) {
            log.warn("Failed to publish configuration governance event {} for revision {}", type, revisionId, e);
        }
    }

    private ConfigurationRevision getRevisionOrThrow(UUID revisionId) {
        return revisionRepository.findById(revisionId)
                .orElseThrow(() -> new RevisionNotFoundException("Revision %s not found".formatted(revisionId)));
    }

    private ApprovalStateSnapshot withPolicy(String action, Supplier<ApprovalStateSnapshot> supplier) {
        try {
            return supplier.get();
        } catch (ManageConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApprovalPolicyException("Approval policy failed to " + action, e);
        }
    }

    private NextStepGuidance submissionGuidance(ApprovalStateSnapshot snapshot, boolean autoRequested) {
        if (snapshot.fullyApproved()) {
            return new NextStepGuidance("Revision approved automatically",
                    List.of("Apply revision when ready"));
        }
        return snapshot.nextPendingStage()
                .map(stage -> new NextStepGuidance(
                        "Awaiting approvals for stage " + stage,
                        autoRequested
                                ? List.of("Monitor approval responses")
                                : List.of("Initiate approval requests for stage " + stage)))
                .orElse(NextStepGuidance.empty());
    }

    private NextStepGuidance approvalGuidance(ApprovalStateSnapshot snapshot) {
        if (snapshot.fullyApproved()) {
            return new NextStepGuidance("All approvals satisfied",
                    List.of("Apply revision"));
        }
        return snapshot.nextPendingStage()
                .map(stage -> new NextStepGuidance(
                        "Awaiting approvals for stage " + stage,
                        List.of("Collect approvals for stage " + stage)))
                .orElse(NextStepGuidance.empty());
    }

    private NextStepGuidance decisionGuidance(ApprovalStateSnapshot snapshot, Decision decision) {
        if (decision == Decision.REJECTED) {
            return new NextStepGuidance("Revision rejected",
                    List.of("Review feedback and resubmit"));
        }
        if (snapshot.fullyApproved()) {
            return new NextStepGuidance("Approvals complete",
                    List.of("Proceed to apply revision"));
        }
        return approvalGuidance(snapshot);
    }
}
