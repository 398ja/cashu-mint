package xyz.tcheeric.cashu.mint.admin.application.service;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.LifecycleCommand;
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
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;

/**
 * Implements {@link ManageMintLifecycleUseCase} with transactional semantics and domain event emission.
 */
public class ManageMintLifecycleInteractor extends AbstractUseCaseInteractor
    implements ManageMintLifecycleUseCase {

    private static final ConfigurationRevisionId INITIAL_REVISION = ConfigurationRevisionId.of(1);
    private static final String CONFIG_VERSION_PARAMETER = "version.tag";

    private final MintRepository mintRepository;
    private final ConfigurationSetRepository configurationSetRepository;
    private final TransactionManager transactionManager;
    private final MintLifecycleEventPublisher eventPublisher;
    private final Clock clock;

    public ManageMintLifecycleInteractor(final MintRepository mintRepository,
                                         final ConfigurationSetRepository configurationSetRepository,
                                         final TransactionManager transactionManager,
                                         final MintLifecycleEventPublisher eventPublisher,
                                         final Clock clock) {
        this.mintRepository = requireNonNull(mintRepository, "mint repository must not be null");
        this.configurationSetRepository = requireNonNull(configurationSetRepository,
            "configuration repository must not be null");
        this.transactionManager = requireNonNull(transactionManager, "transaction manager must not be null");
        this.eventPublisher = requireNonNull(eventPublisher, "event publisher must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ManageMintLifecycleResponse handle(final ManageMintLifecycleRequest request) {
        final ManageMintLifecycleRequest validated = requireRequest(request, "manage mint lifecycle request");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("lifecycle command must not be null");
        }
        final LifecycleCommand command = validated.command();
        final String versionTag = validateVersionTag(validated.versionTag());
        final UUID requestId = resolveRequestId(validated.requestId());
        final String correlationId = resolveCorrelationId(validated.correlationId(), requestId);

        final Map<String, String> configParams = validated.configurationParameters() == null
            ? Map.of() : validated.configurationParameters();

        return switch (command) {
            case CREATE -> createMint(mintId, operatorId, versionTag, requestId, correlationId, configParams);
            case UPDATE_CONFIGURATION -> updateConfiguration(mintId, operatorId, versionTag, requestId, correlationId);
            case PAUSE -> pauseMint(mintId, operatorId, versionTag, requestId, correlationId);
            case RESUME -> resumeMint(mintId, operatorId, versionTag, requestId, correlationId);
            case RETIRE -> retireMint(mintId, operatorId, versionTag, requestId, correlationId);
        };
    }

    private ManageMintLifecycleResponse createMint(final MintId mintId,
                                                   final UUID operatorId,
                                                   final String versionTag,
                                                   final UUID requestId,
                                                   final String correlationId,
                                                   final Map<String, String> configParams) {
        return transactionManager.execute(() -> {
            if (mintRepository.findById(mintId).isPresent()) {
                throw new IllegalStateException("mint already exists: " + mintId.asString());
            }

            final String unit = configParams.getOrDefault("cashu.unit", "sat");
            if (mintRepository.existsActiveByUnit(unit, mintId)) {
                throw new IllegalStateException(
                    "cannot create mint: another mint is already active for unit '" + unit + "'. "
                    + "Pause or retire the existing mint first.");
            }

            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, LifecycleCommand.CREATE, requestId,
                correlationId);
            final Map<String, String> mergedParams = new java.util.HashMap<>(configParams);
            mergedParams.put(CONFIG_VERSION_PARAMETER, versionTag);
            final ConfigurationSet configuration = new ConfigurationSet(INITIAL_REVISION,
                Map.copyOf(mergedParams), auditMetadata);
            final OperatorAccount operatorAccount = new OperatorAccount(operatorId, operatorId.toString(),
                Set.of("MINT_ADMIN"), auditMetadata);
            final NotificationPolicy notificationPolicy = new NotificationPolicy(true, false, Duration.ofMinutes(5),
                auditMetadata);

            final MintAggregate aggregate = MintAggregate.create(mintId, configuration, operatorAccount,
                notificationPolicy, auditMetadata);
            mintRepository.save(aggregate);
            configurationSetRepository.save(mintId, configuration);
            eventPublisher.publish(MintLifecycleEvent.created(mintId,
                aggregate.lifecycleState().value(), configuration.revisionId(), versionTag,
                aggregate.auditMetadata()));
            return buildResponse(aggregate, versionTag);
        });
    }

    private ManageMintLifecycleResponse updateConfiguration(final MintId mintId,
                                                            final UUID operatorId,
                                                            final String versionTag,
                                                            final UUID requestId,
                                                            final String correlationId) {
        return transactionManager.execute(() -> {
            final MintAggregate current = loadExistingAggregate(mintId);
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId,
                LifecycleCommand.UPDATE_CONFIGURATION, requestId, correlationId);
            final ConfigurationRevisionId nextRevision = current.configurationSet().revisionId().next();
            final ConfigurationSet updatedConfiguration = current.configurationSet()
                .updateParameter(CONFIG_VERSION_PARAMETER, versionTag, nextRevision, auditMetadata);
            final MintAggregate updated = current.updateConfiguration(updatedConfiguration, auditMetadata);
            mintRepository.save(updated);
            configurationSetRepository.save(mintId, updatedConfiguration);
            eventPublisher.publish(MintLifecycleEvent.configurationUpdated(mintId,
                updated.lifecycleState().value(), updatedConfiguration.revisionId(), versionTag,
                updated.auditMetadata()));
            return buildResponse(updated, versionTag);
        });
    }

    private ManageMintLifecycleResponse pauseMint(final MintId mintId,
                                                  final UUID operatorId,
                                                  final String versionTag,
                                                  final UUID requestId,
                                                  final String correlationId) {
        return transactionManager.execute(() -> {
            final MintAggregate current = loadExistingAggregate(mintId);
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, LifecycleCommand.PAUSE, requestId,
                correlationId);
            final MintAggregate suspended = current.suspend(auditMetadata);
            mintRepository.save(suspended);
            eventPublisher.publish(MintLifecycleEvent.paused(mintId, current.lifecycleState().value(),
                suspended.lifecycleState().value(), suspended.configurationSet().revisionId(), versionTag,
                suspended.auditMetadata()));
            return buildResponse(suspended, versionTag);
        });
    }

    private ManageMintLifecycleResponse resumeMint(final MintId mintId,
                                                   final UUID operatorId,
                                                   final String versionTag,
                                                   final UUID requestId,
                                                   final String correlationId) {
        return transactionManager.execute(() -> {
            final MintAggregate current = loadExistingAggregate(mintId);
            final String unit = current.configurationSet().parameters()
                .getOrDefault("cashu.unit", "sat");
            if (mintRepository.existsActiveByUnit(unit, mintId)) {
                throw new IllegalStateException(
                    "cannot activate mint: another mint is already active for unit '" + unit + "'");
            }
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, LifecycleCommand.RESUME, requestId,
                correlationId);
            final MintAggregate activated = current.activate(auditMetadata);
            mintRepository.save(activated);
            eventPublisher.publish(MintLifecycleEvent.resumed(mintId, current.lifecycleState().value(),
                activated.lifecycleState().value(), activated.configurationSet().revisionId(), versionTag,
                activated.auditMetadata()));
            return buildResponse(activated, versionTag);
        });
    }

    private ManageMintLifecycleResponse retireMint(final MintId mintId,
                                                   final UUID operatorId,
                                                   final String versionTag,
                                                   final UUID requestId,
                                                   final String correlationId) {
        return transactionManager.execute(() -> {
            final MintAggregate current = loadExistingAggregate(mintId);
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, LifecycleCommand.RETIRE, requestId,
                correlationId);
            final MintAggregate retired = current.decommission(auditMetadata);
            mintRepository.save(retired);
            eventPublisher.publish(MintLifecycleEvent.retired(mintId, current.lifecycleState().value(),
                retired.lifecycleState().value(), retired.configurationSet().revisionId(), versionTag,
                retired.auditMetadata()));
            return buildResponse(retired, versionTag);
        });
    }

    private MintAggregate loadExistingAggregate(final MintId mintId) {
        return mintRepository.findById(mintId)
            .orElseThrow(() -> new IllegalStateException("mint not found: " + mintId.asString()));
    }

    private AuditMetadata createAuditMetadata(final UUID operatorId,
                                             final LifecycleCommand command,
                                             final UUID requestId,
                                             final String correlationId) {
        final Instant timestamp = clock.instant();
        final String actor = operatorId.toString();
        final String action = switch (command) {
            case CREATE -> "Mint created";
            case UPDATE_CONFIGURATION -> "Mint configuration updated";
            case PAUSE -> "Mint paused";
            case RESUME -> "Mint resumed";
            case RETIRE -> "Mint retired";
        };
        return new AuditMetadata(actor, action, timestamp, requestId, correlationId);
    }

    private UUID resolveRequestId(final String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID();
        }
        return validateUuid(requestId, "request id");
    }

    private String resolveCorrelationId(final String correlationId, final UUID requestId) {
        if (correlationId == null || correlationId.isBlank()) {
            return requestId.toString();
        }
        final String sanitized = correlationId.trim();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("correlation id must not be blank");
        }
        return sanitized;
    }

    private ManageMintLifecycleResponse buildResponse(final MintAggregate aggregate, final String versionTag) {
        return new ManageMintLifecycleResponse(aggregate.mintId().asString(),
            aggregate.lifecycleState().value(), versionTag);
    }
}
