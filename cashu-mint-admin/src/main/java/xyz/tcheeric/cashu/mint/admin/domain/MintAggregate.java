package xyz.tcheeric.cashu.mint.admin.domain;

import static java.util.Objects.requireNonNull;

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
    }

    public static MintAggregate create(final MintId mintId,
                                       final ConfigurationSet configurationSet,
                                       final OperatorAccount operatorAccount,
                                       final NotificationPolicy notificationPolicy,
                                       final AuditMetadata metadata) {
        final AuditMetadata creationMetadata = requireNonNull(metadata, "audit metadata must not be null");
        final AuditTrail trail = AuditTrail.create(creationMetadata);
        return new MintAggregate(mintId, LifecycleState.provisioned(), configurationSet, operatorAccount,
            notificationPolicy, trail, creationMetadata);
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
        final LifecycleState nextState = lifecycleState.transitionTo(LifecycleState.State.ACTIVE);
        return withChange(nextState, configurationSet, operatorAccount, notificationPolicy, metadata);
    }

    public MintAggregate suspend(final AuditMetadata metadata) {
        final LifecycleState nextState = lifecycleState.transitionTo(LifecycleState.State.SUSPENDED);
        return withChange(nextState, configurationSet, operatorAccount, notificationPolicy, metadata);
    }

    public MintAggregate decommission(final AuditMetadata metadata) {
        final LifecycleState nextState = lifecycleState.transitionTo(LifecycleState.State.DECOMMISSIONED);
        return withChange(nextState, configurationSet, operatorAccount, notificationPolicy, metadata);
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
        final AuditMetadata audit = requireNonNull(metadata, "audit metadata must not be null");
        final AuditTrail trail = this.auditTrail.append(audit);
        return new MintAggregate(mintId, state, configuration, operator, policy, trail, audit);
    }
}
