package xyz.tcheeric.cashu.mint.admin.domain;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Aggregate root representing the administrative state of a mint.
 */
@Getter
@Accessors(fluent = true)
public final class MintAggregate {

    private final MintId mintId;
    private final LifecycleState lifecycleState;
    private final ConfigurationSet configurationSet;
    private final OperatorAccount operatorAccount;
    private final NotificationPolicy notificationPolicy;
    private final AuditTrail auditTrail;
    private final AuditMetadata auditMetadata;

    private MintAggregate(final MintId mintId,
                          final LifecycleState lifecycleState,
                          final ConfigurationSet configurationSet,
                          final OperatorAccount operatorAccount,
                          final NotificationPolicy notificationPolicy,
                          final AuditTrail auditTrail,
                          final AuditMetadata auditMetadata) {
        this.mintId = requireNonNull(mintId, "mint id must not be null");
        this.lifecycleState = requireNonNull(lifecycleState, "lifecycle state must not be null");
        this.configurationSet = requireNonNull(configurationSet, "configuration set must not be null");
        this.operatorAccount = requireNonNull(operatorAccount, "operator account must not be null");
        this.notificationPolicy = requireNonNull(notificationPolicy, "notification policy must not be null");
        this.auditTrail = requireNonNull(auditTrail, "audit trail must not be null");
        this.auditMetadata = requireNonNull(auditMetadata, "audit metadata must not be null");
        if (auditTrail.entries().isEmpty()) {
            throw new IllegalArgumentException("audit trail must contain at least one entry");
        }
        if (!auditTrail.latestMetadata().equals(auditMetadata)) {
            throw new IllegalArgumentException("latest audit metadata must match aggregate audit metadata");
        }
        final LifecycleContext context = this.auditMetadata.lifecycleContext();
        if (context.hasConfigurationRevision()
            && !context.configurationRevisionId().equals(configurationSet.revisionId())) {
            throw new IllegalArgumentException("audit configuration revision must match aggregate configuration revision");
        }
        if (context.hasNotificationPolicySnapshot()) {
            final NotificationPolicySnapshot snapshot = context.notificationPolicySnapshot();
            final AuditMetadata policyAudit = notificationPolicy.auditMetadata();
            final boolean matchesPolicy = snapshot.emailEnabled() == notificationPolicy.emailEnabled()
                && snapshot.webhookEnabled() == notificationPolicy.webhookEnabled()
                && snapshot.throttleInterval().equals(notificationPolicy.throttleInterval())
                && snapshot.auditActor().equals(policyAudit.actor())
                && snapshot.auditAction().equals(policyAudit.action())
                && snapshot.auditTimestamp().equals(policyAudit.timestamp());
            if (!matchesPolicy) {
                throw new IllegalArgumentException("audit notification policy snapshot must match aggregate policy");
            }
        }
    }

    public static MintAggregate create(final MintId mintId,
                                       final ConfigurationSet configurationSet,
                                       final OperatorAccount operatorAccount,
                                       final NotificationPolicy notificationPolicy,
                                       final AuditMetadata metadata) {
        final AuditMetadata creationMetadata = requireNonNull(metadata, "audit metadata must not be null")
            .withLifecycleContext(configurationSet.revisionId(), notificationPolicy);
        final AuditTrail trail = AuditTrail.create(creationMetadata);
        return new MintAggregate(mintId, LifecycleState.of(LifecycleState.State.PROVISIONING), configurationSet,
            operatorAccount, notificationPolicy, trail, creationMetadata);
    }

    public static MintAggregate reconstitute(final MintId mintId,
                                             final LifecycleState lifecycleState,
                                             final ConfigurationSet configurationSet,
                                             final OperatorAccount operatorAccount,
                                             final NotificationPolicy notificationPolicy,
                                             final AuditTrail auditTrail,
                                             final AuditMetadata auditMetadata) {
        return new MintAggregate(mintId, lifecycleState, configurationSet, operatorAccount, notificationPolicy,
            auditTrail, auditMetadata);
    }

    public MintAggregate activate(final AuditMetadata metadata) {
        return transitionLifecycleTo(LifecycleState.State.ACTIVE, metadata);
    }

    public MintAggregate suspend(final AuditMetadata metadata) {
        return transitionLifecycleTo(LifecycleState.State.SUSPENDED, metadata);
    }

    public MintAggregate decommission(final AuditMetadata metadata) {
        return transitionLifecycleTo(LifecycleState.State.DECOMMISSIONED, metadata);
    }

    public MintAggregate markProvisioned(final AuditMetadata metadata) {
        return transitionLifecycleTo(LifecycleState.State.PROVISIONED, metadata);
    }

    public MintAggregate markProvisionFailed(final AuditMetadata metadata) {
        return transitionLifecycleTo(LifecycleState.State.PROVISION_FAILED, metadata);
    }

    public MintAggregate retryProvisioning(final AuditMetadata metadata) {
        return transitionLifecycleTo(LifecycleState.State.PROVISIONING, metadata);
    }

    public Optional<LifecycleState.TransitionApproval> approvalRequirementsFor(
            final LifecycleState.State targetState) {
        return lifecycleState.approvalRequirementsFor(targetState);
    }

    public MintAggregate updateConfiguration(final ConfigurationSet newConfiguration, final AuditMetadata metadata) {
        final ConfigurationSet configuration = requireNonNull(newConfiguration, "configuration must not be null");
        if (!configuration.revisionId().isAfter(configurationSet.revisionId())) {
            throw new IllegalArgumentException("configuration revision must advance");
        }
        return withChange(lifecycleState, configuration, operatorAccount, notificationPolicy, metadata);
    }

    public MintAggregate updateOperatorAccount(final OperatorAccount newOperator, final AuditMetadata metadata) {
        final OperatorAccount operator = requireNonNull(newOperator, "operator account must not be null");
        return withChange(lifecycleState, configurationSet, operator, notificationPolicy, metadata);
    }

    public MintAggregate updateNotificationPolicy(final NotificationPolicy newPolicy, final AuditMetadata metadata) {
        final NotificationPolicy policy = requireNonNull(newPolicy, "notification policy must not be null");
        return withChange(lifecycleState, configurationSet, operatorAccount, policy, metadata);
    }

    private MintAggregate withChange(final LifecycleState state,
                                     final ConfigurationSet configuration,
                                     final OperatorAccount operator,
                                     final NotificationPolicy policy,
                                     final AuditMetadata metadata) {
        final AuditMetadata audit = requireNonNull(metadata, "audit metadata must not be null")
            .withLifecycleContext(configuration.revisionId(), policy);
        final AuditTrail trail = this.auditTrail.append(audit);
        return new MintAggregate(mintId, state, configuration, operator, policy, trail, audit);
    }

    private MintAggregate transitionLifecycleTo(final LifecycleState.State targetState,
                                                final AuditMetadata metadata) {
        final LifecycleState nextState = lifecycleState.transitionTo(targetState);
        return withChange(nextState, configurationSet, operatorAccount, notificationPolicy, metadata);
    }
}
