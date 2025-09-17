package xyz.tcheeric.cashu.mint.admin.application.service;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApplyConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApprovalChecklist;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApprovalRecordSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApprovalSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.AuditReference;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.AuditSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.AutomationDescriptor;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationPayload;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationSnapshot;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationValueDto;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationValueInput;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationWorkflowResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.DiffArtefact;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.DiffSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.HistoryCheckpoint;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.NextAction;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.PreviewConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ReviewConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ReviewDecision;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.RollbackConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.RollbackMetadata;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.SubmitConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ValidationSummary;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationApprovalRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationNotificationDispatcher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationPolicyEngine;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSecretManager;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationValidator;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository.MintAggregateView;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;
import xyz.tcheeric.cashu.mint.admin.domain.ApprovalRecord;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationAppliedEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationDiff;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionHistory;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRollbackEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSetTransition;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSubmittedEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationValue;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicySnapshot;
import xyz.tcheeric.cashu.mint.admin.domain.ValidationReport;

/**
 * Implements {@link ManageConfigurationUseCase} orchestrating configuration lifecycle operations across repositories
 * and domain services.
 */
public class ManageConfigurationInteractor extends AbstractUseCaseInteractor implements ManageConfigurationUseCase {

    private static final String ACTION_SUBMIT = "Configuration submitted";
    private static final String ACTION_APPROVE = "Configuration approved";
    private static final String ACTION_REJECT = "Configuration rejected";
    private static final String ACTION_APPLY = "Configuration applied";
    private static final String ACTION_ROLLBACK = "Configuration rolled back";

    private final ConfigurationSetRepository configurationSetRepository;
    private final MintRepository mintRepository;
    private final MintAggregateViewRepository mintAggregateViewRepository;
    private final ConfigurationApprovalRepository approvalRepository;
    private final ConfigurationValidator configurationValidator;
    private final ConfigurationPolicyEngine configurationPolicyEngine;
    private final ConfigurationNotificationDispatcher notificationDispatcher;
    private final ConfigurationSecretManager configurationSecretManager;
    private final ConfigurationLifecycleEventPublisher configurationEventPublisher;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public ManageConfigurationInteractor(final ConfigurationSetRepository configurationSetRepository,
                                         final MintRepository mintRepository,
                                         final MintAggregateViewRepository mintAggregateViewRepository,
                                         final ConfigurationApprovalRepository approvalRepository,
                                         final ConfigurationValidator configurationValidator,
                                         final ConfigurationPolicyEngine configurationPolicyEngine,
                                         final ConfigurationNotificationDispatcher notificationDispatcher,
                                         final ConfigurationSecretManager configurationSecretManager,
                                         final ConfigurationLifecycleEventPublisher configurationEventPublisher,
                                         final TransactionManager transactionManager,
                                         final Clock clock) {
        this.configurationSetRepository = requireNonNull(configurationSetRepository,
            "configuration set repository must not be null");
        this.mintRepository = requireNonNull(mintRepository, "mint repository must not be null");
        this.mintAggregateViewRepository = requireNonNull(mintAggregateViewRepository,
            "mint aggregate view repository must not be null");
        this.approvalRepository = requireNonNull(approvalRepository, "approval repository must not be null");
        this.configurationValidator = requireNonNull(configurationValidator, "configuration validator must not be null");
        this.configurationPolicyEngine = requireNonNull(configurationPolicyEngine,
            "configuration policy engine must not be null");
        this.notificationDispatcher = requireNonNull(notificationDispatcher,
            "notification dispatcher must not be null");
        this.configurationSecretManager = requireNonNull(configurationSecretManager,
            "configuration secret manager must not be null");
        this.configurationEventPublisher = requireNonNull(configurationEventPublisher,
            "configuration event publisher must not be null");
        this.transactionManager = requireNonNull(transactionManager, "transaction manager must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ConfigurationWorkflowResponse submit(final SubmitConfigurationCommand command) {
        final SubmitConfigurationCommand validated = requireRequest(command, "submit configuration command");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        final String versionTag = validated.versionTag() == null ? null : validateVersionTag(validated.versionTag());
        final UUID requestId = resolveRequestId(validated.requestId());
        final String correlationId = resolveCorrelationId(validated.correlationId(), requestId);
        final Map<String, ConfigurationValueInput> parameterInputs = requireParameters(validated.payload());
        return submitConfiguration(mintId, operatorId, validated, parameterInputs, requestId, correlationId, versionTag);
    }

    @Override
    public ConfigurationWorkflowResponse preview(final PreviewConfigurationCommand command) {
        final PreviewConfigurationCommand validated = requireRequest(command, "preview configuration command");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        final ConfigurationRevisionId revisionId = validateConfigurationRevision(validated.targetRevision());
        final String versionTag = validated.versionTag() == null ? null : validateVersionTag(validated.versionTag());
        final UUID requestId = resolveRequestId(validated.requestId());
        final String correlationId = resolveCorrelationId(validated.correlationId(), requestId);
        if (validated.includeValidation()) {
            return validateConfiguration(mintId, operatorId, revisionId, validated, requestId, correlationId, versionTag);
        }
        return previewDiff(mintId, revisionId, versionTag, validated);
    }

    @Override
    public ConfigurationWorkflowResponse review(final ReviewConfigurationCommand command) {
        final ReviewConfigurationCommand validated = requireRequest(command, "review configuration command");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        final ConfigurationRevisionId revisionId = validateConfigurationRevision(validated.targetRevision());
        final String versionTag = validated.versionTag() == null ? null : validateVersionTag(validated.versionTag());
        final UUID requestId = resolveRequestId(validated.requestId());
        final String correlationId = resolveCorrelationId(validated.correlationId(), requestId);
        return switch (validated.decision()) {
            case APPROVE -> approveConfiguration(mintId, operatorId, revisionId, validated, requestId, correlationId,
                versionTag);
            case REJECT -> rejectConfiguration(mintId, operatorId, revisionId, validated, requestId, correlationId,
                versionTag);
        };
    }

    @Override
    public ConfigurationWorkflowResponse apply(final ApplyConfigurationCommand command) {
        final ApplyConfigurationCommand validated = requireRequest(command, "apply configuration command");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        final ConfigurationRevisionId revisionId = validateConfigurationRevision(validated.targetRevision());
        final String versionTag = validated.versionTag() == null ? null : validateVersionTag(validated.versionTag());
        final UUID requestId = resolveRequestId(validated.requestId());
        final String correlationId = resolveCorrelationId(validated.correlationId(), requestId);
        return applyConfiguration(mintId, operatorId, revisionId, validated, requestId, correlationId, versionTag);
    }

    @Override
    public ConfigurationWorkflowResponse rollback(final RollbackConfigurationCommand command) {
        final RollbackConfigurationCommand validated = requireRequest(command, "rollback configuration command");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        final ConfigurationRevisionId revisionId = validateConfigurationRevision(validated.targetRevision());
        final String versionTag = validated.versionTag() == null ? null : validateVersionTag(validated.versionTag());
        final UUID requestId = resolveRequestId(validated.requestId());
        final String correlationId = resolveCorrelationId(validated.correlationId(), requestId);
        return rollbackConfiguration(mintId, operatorId, revisionId, validated, requestId, correlationId, versionTag);
    }

    private ConfigurationWorkflowResponse submitConfiguration(final MintId mintId,
                                                               final UUID operatorId,
                                                               final SubmitConfigurationCommand command,
                                                               final Map<String, ConfigurationValueInput> parameterInputs,
                                                               final UUID requestId,
                                                               final String correlationId,
                                                               final String versionTag) {
        return transactionManager.execute(() -> {
            final MintAggregate aggregate = loadMint(mintId);
            final ConfigurationSet current = aggregate.configurationSet();
            final ConfigurationRevisionId nextRevision = determineNextRevision(mintId, current.revisionId());
            final Map<String, ConfigurationValue> updatedParameters = new LinkedHashMap<>(current.parameters());
            parameterInputs.forEach((key, valueInput) -> {
                if (valueInput == null) {
                    throw new IllegalArgumentException("configuration parameter input must not be null for key: " + key);
                }
                updatedParameters.put(requireParameterKey(key),
                    configurationSecretManager.prepareValue(mintId, nextRevision, key, valueInput));
            });

            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, ACTION_SUBMIT, command, requestId,
                correlationId).withLifecycleContext(nextRevision, aggregate.notificationPolicy());
            final ConfigurationSet newRevision = new ConfigurationSet(nextRevision, updatedParameters, auditMetadata,
                ConfigurationRevisionState.DRAFT,
                ConfigurationRevisionHistory.initial(ConfigurationRevisionState.DRAFT, auditMetadata), null, null);

            configurationSetRepository.save(mintId, newRevision);
            final ConfigurationSubmittedEvent event = newRevision.submissionEvent(current);
            configurationEventPublisher.publish(mintId, event);
            notificationDispatcher.dispatch(mintId, event, newRevision);

            return buildResponse(mintId, newRevision, aggregate, event.diff(), newRevision.validationReport(),
                newRevision.approvalRecord(), versionTag, command.approvalChecklist(), null);
        });
    }

    private ConfigurationWorkflowResponse previewDiff(final MintId mintId,
                                                       final ConfigurationRevisionId revisionId,
                                                       final String versionTag,
                                                       final PreviewConfigurationCommand command) {
        final ConfigurationSet target = loadConfiguration(mintId, revisionId);
        final Optional<MintAggregate> aggregate = mintRepository.findById(mintId);
        final ConfigurationDiff diff = aggregate.map(agg -> target.diff(agg.configurationSet()))
            .orElseGet(ConfigurationDiff::empty);
        return buildResponse(mintId, target, aggregate.orElse(null), diff, target.validationReport(),
            target.approvalRecord(), versionTag, List.of(), null);
    }

    private ConfigurationWorkflowResponse validateConfiguration(final MintId mintId,
                                                                 final UUID operatorId,
                                                                 final ConfigurationRevisionId revisionId,
                                                                 final PreviewConfigurationCommand command,
                                                                 final UUID requestId,
                                                                 final String correlationId,
                                                                 final String versionTag) {
        return transactionManager.execute(() -> {
            final ConfigurationSet configuration = loadConfiguration(mintId, revisionId);
            final Optional<MintAggregate> aggregate = mintRepository.findById(mintId);
            final ValidationReport report = configurationValidator.validate(mintId, configuration);
            final ConfigurationSetTransition transition = configuration.recordValidation(report);
            configurationSetRepository.save(mintId, transition.configurationSet());

            configurationEventPublisher.publish(mintId, transition.lifecycleEvent());
            notificationDispatcher.dispatch(mintId, transition.lifecycleEvent(), transition.configurationSet());

            final ConfigurationWorkflowResponse response = buildResponse(mintId, transition.configurationSet(),
                aggregate.orElse(null), null, report, transition.configurationSet().approvalRecord(), versionTag,
                List.of(), null);
            if (!report.valid()) {
                throw new ConfigurationValidationException(mintId, revisionId, report, response);
            }
            return response;
        });
    }

    private ConfigurationWorkflowResponse approveConfiguration(final MintId mintId,
                                                                final UUID operatorId,
                                                                final ConfigurationRevisionId revisionId,
                                                                final ReviewConfigurationCommand command,
                                                                final UUID requestId,
                                                                final String correlationId,
                                                                final String versionTag) {
        return transactionManager.execute(() -> {
            final ConfigurationSet configuration = loadConfiguration(mintId, revisionId);
            final MintAggregate aggregate = loadMint(mintId);
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, ACTION_APPROVE, command, requestId,
                correlationId).withLifecycleContext(configuration.revisionId(), aggregate.notificationPolicy());
            final ApprovalRecord approvalRecord = new ApprovalRecord(configuration.revisionId(), auditMetadata,
                clock.instant(), safeList(command.approvalChecklist()),
                NotificationPolicySnapshot.fromPolicy(aggregate.notificationPolicy()));
            final ConfigurationSetTransition transition = configuration.approve(approvalRecord);
            configurationSetRepository.save(mintId, transition.configurationSet());
            approvalRepository.recordApproval(mintId, approvalRecord);

            configurationEventPublisher.publish(mintId, transition.lifecycleEvent());
            notificationDispatcher.dispatch(mintId, transition.lifecycleEvent(), transition.configurationSet());

            return buildResponse(mintId, transition.configurationSet(), aggregate, null,
                transition.configurationSet().validationReport(), approvalRecord, versionTag,
                command.approvalChecklist(), null);
        });
    }

    private ConfigurationWorkflowResponse rejectConfiguration(final MintId mintId,
                                                               final UUID operatorId,
                                                               final ConfigurationRevisionId revisionId,
                                                               final ReviewConfigurationCommand command,
                                                               final UUID requestId,
                                                               final String correlationId,
                                                               final String versionTag) {
        return transactionManager.execute(() -> {
            final ConfigurationSet configuration = loadConfiguration(mintId, revisionId);
            final Optional<MintAggregate> aggregate = mintRepository.findById(mintId);
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, ACTION_REJECT, command, requestId,
                correlationId).withLifecycleContext(configuration.revisionId(),
                aggregate.map(MintAggregate::notificationPolicy).orElse(null));
            final List<String> rejectionIssues = rejectionReasons(command.rejectionReasons());
            final ValidationReport report = ValidationReport.failure(configuration.revisionId(), rejectionIssues,
                auditMetadata, auditMetadata.timestamp(), Map.of());
            final ConfigurationSetTransition transition = configuration.recordValidation(report);
            configurationSetRepository.save(mintId, transition.configurationSet());

            configurationEventPublisher.publish(mintId, transition.lifecycleEvent());
            notificationDispatcher.dispatch(mintId, transition.lifecycleEvent(), transition.configurationSet());

            return buildResponse(mintId, transition.configurationSet(), aggregate.orElse(null), null, report,
                transition.configurationSet().approvalRecord(), versionTag, command.approvalChecklist(), null);
        });
    }

    private ConfigurationWorkflowResponse applyConfiguration(final MintId mintId,
                                                              final UUID operatorId,
                                                              final ConfigurationRevisionId revisionId,
                                                              final ApplyConfigurationCommand command,
                                                              final UUID requestId,
                                                              final String correlationId,
                                                              final String versionTag) {
        return transactionManager.execute(() -> {
            final MintAggregate aggregate = loadMint(mintId);
            final ConfigurationSet target = loadConfiguration(mintId, revisionId);
            if (target.approvalRecord() == null) {
                throw new MissingApprovalException(mintId, revisionId);
            }
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, ACTION_APPLY, command, requestId,
                correlationId).withLifecycleContext(target.revisionId(), aggregate.notificationPolicy());
            final ConfigurationSetTransition transition = target.apply(aggregate.configurationSet(), auditMetadata);
            final ConfigurationSet applied = transition.configurationSet();
            configurationSetRepository.save(mintId, applied);

            final MintAggregate updatedAggregate = aggregate.updateConfiguration(applied, applied.auditMetadata());
            mintRepository.save(updatedAggregate);

            configurationEventPublisher.publish(mintId, transition.lifecycleEvent());
            notificationDispatcher.dispatch(mintId, transition.lifecycleEvent(), applied);

            final ConfigurationDiff diff = extractDiff(transition.lifecycleEvent());
            return buildResponse(mintId, applied, updatedAggregate, diff, applied.validationReport(),
                applied.approvalRecord(), versionTag, List.of(), null);
        });
    }

    private ConfigurationWorkflowResponse rollbackConfiguration(final MintId mintId,
                                                                 final UUID operatorId,
                                                                 final ConfigurationRevisionId revisionId,
                                                                 final RollbackConfigurationCommand command,
                                                                 final UUID requestId,
                                                                 final String correlationId,
                                                                 final String versionTag) {
        return transactionManager.execute(() -> {
            final MintAggregate aggregate = loadMint(mintId);
            final ConfigurationSet target = loadConfiguration(mintId, revisionId);
            final AuditMetadata auditMetadata = createAuditMetadata(operatorId, ACTION_ROLLBACK, command, requestId,
                correlationId).withLifecycleContext(target.revisionId(), aggregate.notificationPolicy());
            try {
                final ConfigurationSetTransition transition = target.rollbackFrom(aggregate.configurationSet(),
                    auditMetadata);
                configurationSetRepository.save(mintId, transition.configurationSet());
                configurationEventPublisher.publish(mintId, transition.lifecycleEvent());
                notificationDispatcher.dispatch(mintId, transition.lifecycleEvent(), transition.configurationSet());
                final ConfigurationDiff diff = extractDiff(transition.lifecycleEvent());
                final RollbackMetadata metadata = toRollbackMetadata(transition.lifecycleEvent(), command,
                    auditMetadata);
                return buildResponse(mintId, transition.configurationSet(), aggregate, diff,
                    transition.configurationSet().validationReport(), transition.configurationSet().approvalRecord(),
                    versionTag, List.of(), metadata);
            } catch (final IllegalStateException ex) {
                throw new RollbackConflictException(mintId, revisionId, ex.getMessage(), ex);
            }
        });
    }

    private MintAggregate loadMint(final MintId mintId) {
        return mintRepository.findById(mintId)
            .orElseThrow(() -> new IllegalStateException("mint not found: " + mintId.asString()));
    }

    private ConfigurationSet loadConfiguration(final MintId mintId, final ConfigurationRevisionId revisionId) {
        return configurationSetRepository.findByRevision(mintId, revisionId)
            .orElseThrow(() -> new IllegalStateException(
                "configuration revision %s not found for mint %s".formatted(revisionId.value(), mintId.asString())));
    }

    private ConfigurationRevisionId determineNextRevision(final MintId mintId,
                                                          final ConfigurationRevisionId currentRevision) {
        final List<ConfigurationSet> history = configurationSetRepository.findByMintId(mintId);
        final ConfigurationRevisionId latest = history.stream()
            .map(ConfigurationSet::revisionId)
            .max(Comparator.naturalOrder())
            .orElse(currentRevision);
        return latest.next();
    }

    private UUID resolveRequestId(final String rawRequestId) {
        if (rawRequestId == null || rawRequestId.isBlank()) {
            return UUID.randomUUID();
        }
        return validateUuid(rawRequestId, "request id");
    }

    private String resolveCorrelationId(final String rawCorrelationId, final UUID requestId) {
        if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
            return requestId.toString();
        }
        final String sanitized = rawCorrelationId.trim();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("correlation id must not be blank");
        }
        return sanitized;
    }

    private AuditMetadata createAuditMetadata(final UUID operatorId,
                                              final String action,
                                              final ManageConfigurationUseCase.WorkflowCommand command,
                                              final UUID requestId,
                                              final String correlationId) {
        final Instant timestamp = clock.instant();
        final String actor = operatorId.toString();
        final List<String> reasonCodes = safeList(command.reasonCodes());
        final List<String> ticketReferences = safeList(command.ticketReferences());
        return new AuditMetadata(actor, action, timestamp, reasonCodes, ticketReferences, AutomationContext.manual(),
            null, requestId, correlationId);
    }

    private ConfigurationWorkflowResponse buildResponse(final MintId mintId,
                                                        final ConfigurationSet configurationSet,
                                                        final MintAggregate aggregate,
                                                        final ConfigurationDiff diff,
                                                        final ValidationReport validationReport,
                                                        final ApprovalRecord approvalRecord,
                                                        final String versionTagOverride,
                                                        final List<String> requestedChecklist,
                                                        final RollbackMetadata rollbackMetadata) {
        final ConfigurationDiff effectiveDiff = diff != null
            ? diff
            : aggregate == null ? ConfigurationDiff.empty() : configurationSet.diff(aggregate.configurationSet());
        final DiffArtefact diffArtefact = toDiffArtefact(effectiveDiff);
        final DiffSummary diffSummary = toDiffSummary(diffArtefact);
        final ValidationSummary validationSummary = toValidationSummary(validationReport);
        final ApprovalSummary approvalSummary = toApprovalSummary(approvalRecord, configurationSet.state());
        final ApprovalChecklist approvalChecklist = toApprovalChecklist(requestedChecklist, approvalRecord);
        final List<ApprovalRecordSummary> approvalHistory = approvalRepository
            .findApprovals(mintId, configurationSet.revisionId()).stream()
            .map(this::toApprovalRecordSummary)
            .toList();
        final AuditSummary auditSummary = toAuditSummary(configurationSet.auditMetadata());
        final List<HistoryCheckpoint> history = configurationSet.history().asList().stream()
            .map(checkpoint -> new HistoryCheckpoint(checkpoint.state(), toAuditSummary(checkpoint.metadata())))
            .toList();
        final NextAction nextAction = determineNextAction(mintId, configurationSet);
        final MintAggregateView view = mintAggregateViewRepository.findById(mintId).orElse(null);
        final String activeRevision = view == null ? null : Long.toString(view.configurationRevisionId().value());
        final String versionTag = versionTagOverride != null
            ? versionTagOverride
            : view == null ? null : view.versionTag();
        final ConfigurationSnapshot requestedSnapshot = toSnapshot(configurationSet, versionTag);
        final ConfigurationSnapshot activeSnapshot = aggregate == null
            ? null
            : toSnapshot(aggregate.configurationSet(), view == null ? null : view.versionTag());
        return new ConfigurationWorkflowResponse(mintId.asString(),
            Long.toString(configurationSet.revisionId().value()), activeRevision, versionTag,
            configurationSet.state(), requestedSnapshot, activeSnapshot, diffSummary, diffArtefact, validationSummary,
            approvalSummary, approvalChecklist, approvalHistory, auditSummary, history, rollbackMetadata, nextAction);
    }

    private ManageConfigurationUseCase.DiffArtefact toDiffArtefact(final ConfigurationDiff diff) {
        if (diff == null || diff.isEmpty()) {
            return new ManageConfigurationUseCase.DiffArtefact(Map.of(), Map.of(), Map.of());
        }
        final Map<String, ConfigurationValueDto> added = new LinkedHashMap<>();
        diff.added().forEach((key, value) -> added.put(key, toValueDto(value)));
        final Map<String, ManageConfigurationUseCase.ParameterChangeDto> changed = new LinkedHashMap<>();
        diff.changed().forEach((key, change) -> changed.put(key,
            new ManageConfigurationUseCase.ParameterChangeDto(toValueDto(change.previousValue()),
                toValueDto(change.nextValue()))));
        final Map<String, ConfigurationValueDto> removed = new LinkedHashMap<>();
        diff.removed().forEach((key, value) -> removed.put(key, toValueDto(value)));
        return new ManageConfigurationUseCase.DiffArtefact(Map.copyOf(added), Map.copyOf(changed),
            Map.copyOf(removed));
    }

    private DiffSummary toDiffSummary(final DiffArtefact artefact) {
        return new DiffSummary(artefact.added().size(), artefact.changed().size(), artefact.removed().size());
    }

    private ConfigurationValueDto toValueDto(final ConfigurationValue value) {
        if (value == null) {
            return new ConfigurationValueDto(null, false, null);
        }
        if (value.isSecret()) {
            return new ConfigurationValueDto(null, true, value.secret().reference());
        }
        return new ConfigurationValueDto(value.resolvedValue(), false, null);
    }

    private ConfigurationSnapshot toSnapshot(final ConfigurationSet configurationSet, final String versionTag) {
        if (configurationSet == null) {
            return null;
        }
        final Map<String, ConfigurationValueDto> values = new LinkedHashMap<>();
        configurationSet.parameters().forEach((key, value) -> values.put(key, toValueDto(value)));
        return new ConfigurationSnapshot(Long.toString(configurationSet.revisionId().value()),
            Map.copyOf(values), configurationSet.auditMetadata().timestamp(), versionTag);
    }

    private ManageConfigurationUseCase.ValidationSummary toValidationSummary(final ValidationReport report) {
        if (report == null) {
            return new ManageConfigurationUseCase.ValidationSummary(false, true, List.of(), Map.of(), null, null);
        }
        return new ManageConfigurationUseCase.ValidationSummary(true, report.valid(), report.issues(),
            report.artefacts(), report.validator().actor(), report.validatedAt());
    }

    private ApprovalSummary toApprovalSummary(final ApprovalRecord approvalRecord,
                                              final ConfigurationRevisionState state) {
        if (approvalRecord == null) {
            final ReviewDecision decision = state == ConfigurationRevisionState.REJECTED
                ? ReviewDecision.REJECT
                : null;
            return new ApprovalSummary(false, null, null, List.of(), decision);
        }
        return new ApprovalSummary(true, approvalRecord.approver().actor(), approvalRecord.approvedAt(),
            approvalRecord.conditions(), ReviewDecision.APPROVE);
    }

    private ManageConfigurationUseCase.ApprovalRecordSummary toApprovalRecordSummary(final ApprovalRecord record) {
        final AuditMetadata metadata = record.approver();
        final String requestId = metadata.requestId() == null ? null : metadata.requestId().toString();
        return new ManageConfigurationUseCase.ApprovalRecordSummary(metadata.actor(), record.approvedAt(),
            record.conditions(), requestId, metadata.correlationId(), ReviewDecision.APPROVE);
    }

    private ApprovalChecklist toApprovalChecklist(final List<String> requestedChecklist,
                                                  final ApprovalRecord approvalRecord) {
        final List<String> required = safeList(requestedChecklist);
        final List<String> satisfied = approvalRecord == null ? List.of() : safeList(approvalRecord.conditions());
        final List<String> outstanding = required.stream()
            .filter(item -> !satisfied.contains(item))
            .toList();
        return new ApprovalChecklist(required, satisfied, outstanding);
    }

    private AuditSummary toAuditSummary(final AuditMetadata metadata) {
        final String requestId = metadata.requestId() == null ? null : metadata.requestId().toString();
        final AuditReference reference = new AuditReference(requestId, metadata.correlationId());
        final AutomationDescriptor automation = toAutomationDescriptor(metadata.automationContext());
        return new AuditSummary(metadata.actor(), metadata.action(), metadata.timestamp(), metadata.reasonCodes(),
            metadata.ticketReferences(), reference, automation);
    }

    private AutomationDescriptor toAutomationDescriptor(final AutomationContext context) {
        if (context == null) {
            return new AutomationDescriptor(false, null, null);
        }
        return new AutomationDescriptor(context.automated(), context.system(), context.runId());
    }

    private NextAction determineNextAction(final MintId mintId, final ConfigurationSet configurationSet) {
        final NextAction recommended = configurationPolicyEngine.nextActionFor(mintId, configurationSet);
        if (recommended != null) {
            return recommended;
        }
        return switch (configurationSet.state()) {
            case DRAFT -> NextAction.VALIDATE;
            case VALIDATED -> NextAction.APPROVE;
            case APPROVED -> NextAction.APPLY;
            case APPLIED, ROLLED_BACK -> NextAction.NONE;
            case REJECTED -> NextAction.DIFF;
        };
    }

    private ConfigurationDiff extractDiff(final ConfigurationLifecycleEvent event) {
        if (event instanceof ConfigurationSubmittedEvent submittedEvent) {
            return submittedEvent.diff();
        }
        if (event instanceof ConfigurationAppliedEvent appliedEvent) {
            return appliedEvent.diff();
        }
        if (event instanceof ConfigurationRollbackEvent rollbackEvent) {
            return rollbackEvent.diff();
        }
        return null;
    }

    private RollbackMetadata toRollbackMetadata(final ConfigurationLifecycleEvent event,
                                                final RollbackConfigurationCommand command,
                                                final AuditMetadata metadata) {
        if (!(event instanceof ConfigurationRollbackEvent rollbackEvent)) {
            return null;
        }
        final String source = Long.toString(rollbackEvent.revisionId().value());
        final String target = Long.toString(rollbackEvent.targetRevisionId().value());
        final String requestId = metadata.requestId() == null ? null : metadata.requestId().toString();
        final AuditReference reference = new AuditReference(requestId, metadata.correlationId());
        final String externalReference = command.auditReference() == null || command.auditReference().isBlank()
            ? null
            : command.auditReference().trim();
        return new RollbackMetadata(source, target, metadata.timestamp(), command.rollbackReason(), reference,
            externalReference);
    }

    private List<String> rejectionReasons(final List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of("Rejected by operator");
        }
        return reasons.stream()
            .map(reason -> {
                if (reason == null) {
                    throw new IllegalArgumentException("rejection reason must not be null");
                }
                final String sanitized = reason.strip();
                if (sanitized.isEmpty()) {
                    throw new IllegalArgumentException("rejection reason must not be blank");
                }
                return sanitized;
            })
            .toList();
    }

    private Map<String, ConfigurationValueInput> requireParameters(final ConfigurationPayload payload) {
        if (payload == null || payload.parameters().isEmpty()) {
            throw new IllegalArgumentException("configuration parameters must be supplied for submission");
        }
        return Map.copyOf(payload.parameters());
    }

    private String requireParameterKey(final String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("parameter key must not be blank");
        }
        return key.trim();
    }

    private List<String> safeList(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values);
    }
}
