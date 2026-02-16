package xyz.tcheeric.cashu.mint.admin.application.service;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Implements {@link ManageConfigurationUseCase} with transactional semantics,
 * supporting APPLY, ROLLBACK, VALIDATE, and DIFF commands.
 */
public class ManageConfigurationInteractor extends AbstractUseCaseInteractor
    implements ManageConfigurationUseCase {

    private final MintRepository mintRepository;
    private final ConfigurationSetRepository configurationSetRepository;
    private final TransactionManager transactionManager;
    private final MintLifecycleEventPublisher eventPublisher;
    private final Clock clock;

    public ManageConfigurationInteractor(final MintRepository mintRepository,
                                         final ConfigurationSetRepository configurationSetRepository,
                                         final TransactionManager transactionManager,
                                         final MintLifecycleEventPublisher eventPublisher,
                                         final Clock clock) {
        this.mintRepository = requireNonNull(mintRepository, "mint repository must not be null");
        this.configurationSetRepository = requireNonNull(configurationSetRepository,
            "configuration set repository must not be null");
        this.transactionManager = requireNonNull(transactionManager, "transaction manager must not be null");
        this.eventPublisher = requireNonNull(eventPublisher, "event publisher must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ManageConfigurationResponse handle(final ManageConfigurationRequest request) {
        final ManageConfigurationRequest validated = requireRequest(request, "manage configuration request");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("configuration command must not be null");
        }
        final String versionTag = validateVersionTag(validated.versionTag());

        return switch (validated.command()) {
            case APPLY -> applyConfiguration(mintId, operatorId, validated.targetRevision(),
                versionTag, validated.parameters());
            case ROLLBACK -> rollbackConfiguration(mintId, operatorId, validated.targetRevision(), versionTag);
            case VALIDATE -> validateConfiguration(mintId, validated.parameters());
            case DIFF -> diffConfiguration(mintId, validated.targetRevision());
        };
    }

    private ManageConfigurationResponse applyConfiguration(final MintId mintId,
                                                            final UUID operatorId,
                                                            final String baseRevision,
                                                            final String versionTag,
                                                            final Map<String, String> parameters) {
        return transactionManager.execute(() -> {
            final MintAggregate current = loadExistingAggregate(mintId);
            final ConfigurationSet currentConfig = current.configurationSet();

            if (baseRevision != null && !baseRevision.isBlank()) {
                final ConfigurationRevisionId baseRevId = validateConfigurationRevision(baseRevision);
                if (!baseRevId.equals(currentConfig.revisionId())) {
                    throw new IllegalStateException(
                        "revision conflict: current is " + currentConfig.revisionId().value()
                            + " but base was " + baseRevId.value());
                }
            }

            final Map<String, String> merged = mergeParameters(currentConfig.parameters(), parameters);
            final ConfigurationRevisionId nextRevision = currentConfig.revisionId().next();
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, "Configuration applied");
            final ConfigurationSet newConfig = new ConfigurationSet(nextRevision, merged, auditMetadata);

            final MintAggregate updated = current.updateConfiguration(newConfig, auditMetadata);
            mintRepository.save(updated);
            configurationSetRepository.save(mintId, newConfig);
            eventPublisher.publish(MintLifecycleEvent.configurationUpdated(mintId,
                updated.lifecycleState().value(), nextRevision, versionTag, updated.auditMetadata()));

            return new ManageConfigurationResponse(mintId.asString(),
                String.valueOf(nextRevision.value()), versionTag, newConfig.parameters(),
                "Configuration applied");
        });
    }

    private ManageConfigurationResponse rollbackConfiguration(final MintId mintId,
                                                               final UUID operatorId,
                                                               final String targetRevision,
                                                               final String versionTag) {
        final ConfigurationRevisionId targetRevId = validateConfigurationRevision(targetRevision);

        return transactionManager.execute(() -> {
            final MintAggregate current = loadExistingAggregate(mintId);
            final ConfigurationSet targetConfig = configurationSetRepository
                .findByRevision(mintId, targetRevId)
                .orElseThrow(() -> new IllegalStateException(
                    "revision not found: " + targetRevId.value()));

            final ConfigurationRevisionId nextRevision = current.configurationSet().revisionId().next();
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId,
                "Rolled back to revision " + targetRevId.value());
            final ConfigurationSet rolledBack = new ConfigurationSet(nextRevision,
                targetConfig.parameters(), auditMetadata);

            final MintAggregate updated = current.updateConfiguration(rolledBack, auditMetadata);
            mintRepository.save(updated);
            configurationSetRepository.save(mintId, rolledBack);
            eventPublisher.publish(MintLifecycleEvent.configurationUpdated(mintId,
                updated.lifecycleState().value(), nextRevision, versionTag, updated.auditMetadata()));

            return new ManageConfigurationResponse(mintId.asString(),
                String.valueOf(nextRevision.value()), versionTag, rolledBack.parameters(),
                "Rolled back to revision " + targetRevId.value());
        });
    }

    private ManageConfigurationResponse validateConfiguration(final MintId mintId,
                                                               final Map<String, String> parameters) {
        final MintAggregate current = loadExistingAggregate(mintId);
        final ConfigurationSet currentConfig = current.configurationSet();
        final Map<String, String> merged = mergeParameters(currentConfig.parameters(), parameters);

        if (merged.isEmpty()) {
            throw new IllegalArgumentException("configuration must contain at least one parameter");
        }

        return new ManageConfigurationResponse(mintId.asString(),
            String.valueOf(currentConfig.revisionId().value()),
            currentConfig.parameters().getOrDefault("version.tag", "unknown"),
            merged, "Validation passed");
    }

    private ManageConfigurationResponse diffConfiguration(final MintId mintId,
                                                           final String targetRevision) {
        final ConfigurationRevisionId targetRevId = validateConfigurationRevision(targetRevision);
        final MintAggregate current = loadExistingAggregate(mintId);
        final ConfigurationSet currentConfig = current.configurationSet();
        final ConfigurationSet targetConfig = configurationSetRepository
            .findByRevision(mintId, targetRevId)
            .orElseThrow(() -> new IllegalStateException(
                "revision not found: " + targetRevId.value()));

        final Map<String, String> diff = computeDiff(currentConfig.parameters(), targetConfig.parameters());

        return new ManageConfigurationResponse(mintId.asString(),
            String.valueOf(currentConfig.revisionId().value()),
            currentConfig.parameters().getOrDefault("version.tag", "unknown"),
            diff, "Diff between current (rev " + currentConfig.revisionId().value()
                + ") and target (rev " + targetRevId.value() + ")");
    }

    private MintAggregate loadExistingAggregate(final MintId mintId) {
        return mintRepository.findById(mintId)
            .orElseThrow(() -> new IllegalStateException("mint not found: " + mintId.asString()));
    }

    private AuditMetadata createAuditMetadata(final UUID operatorId, final String action) {
        return new AuditMetadata(operatorId.toString(), action, clock.instant());
    }

    private static Map<String, String> mergeParameters(final Map<String, String> current,
                                                        final Map<String, String> proposed) {
        final Map<String, String> merged = new LinkedHashMap<>(current);
        if (proposed != null) {
            for (final Map.Entry<String, String> entry : proposed.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return Map.copyOf(merged);
    }

    private static Map<String, String> computeDiff(final Map<String, String> current,
                                                    final Map<String, String> target) {
        final Map<String, String> diff = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : current.entrySet()) {
            if (!target.containsKey(entry.getKey())) {
                diff.put(entry.getKey(), "removed (was: " + entry.getValue() + ")");
            } else if (!entry.getValue().equals(target.get(entry.getKey()))) {
                diff.put(entry.getKey(), entry.getValue() + " -> " + target.get(entry.getKey()));
            }
        }
        for (final Map.Entry<String, String> entry : target.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                diff.put(entry.getKey(), "added: " + entry.getValue());
            }
        }
        return Map.copyOf(diff);
    }
}
