package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ApplyConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationPayload;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationValueInput;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationWorkflowResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.NextAction;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.PreviewConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ReviewConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ReviewDecision;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.RollbackConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.SubmitConfigurationCommand;
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
import xyz.tcheeric.cashu.mint.admin.domain.AuditTrail;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationAppliedEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationApprovedEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionHistory;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRollbackEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSecret;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSecret.SecretMaterialization;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSubmittedEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationValidatedEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationValidationFailedEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationValue;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicySnapshot;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;
import xyz.tcheeric.cashu.mint.admin.domain.ValidationReport;

@ExtendWith(MockitoExtension.class)
class BaseManageConfigurationInteractorTest {

    private static final Instant NOW = Instant.parse("2024-04-01T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MintId MINT_ID = MintId.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID OPERATOR_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

    @Mock
    private ConfigurationSetRepository configurationSetRepository;
    @Mock
    private MintRepository mintRepository;
    @Mock
    private MintAggregateViewRepository mintAggregateViewRepository;
    @Mock
    private ConfigurationApprovalRepository approvalRepository;
    @Mock
    private ConfigurationValidator configurationValidator;
    @Mock
    private ConfigurationPolicyEngine configurationPolicyEngine;
    @Mock
    private ConfigurationNotificationDispatcher notificationDispatcher;
    @Mock
    private ConfigurationSecretManager configurationSecretManager;
    @Mock
    private ConfigurationLifecycleEventPublisher eventPublisher;
    @Mock
    private TransactionManager transactionManager;

    private ManageConfigurationInteractor interactor;
    private NotificationPolicy notificationPolicy;
    private ConfigurationSet activeConfiguration;
    private MintAggregate activeAggregate;
    private MintAggregateView activeView;
    private Map<MintId, Map<ConfigurationRevisionId, List<ApprovalRecord>>> recordedApprovals;

    @BeforeEach
    void setUp() {
        recordedApprovals = new HashMap<>();

        notificationPolicy = new NotificationPolicy(true, true, Duration.ofMinutes(10),
            new AuditMetadata("system", "Policy created", NOW.minusSeconds(3_600)));
        activeConfiguration = initialAppliedConfiguration(ConfigurationRevisionId.of(1));
        activeAggregate = mintAggregate(activeConfiguration);
        activeView = new MintAggregateView(MINT_ID, activeAggregate.lifecycleState().value(),
            activeConfiguration.revisionId(), "v1.0.0", NOW.minusSeconds(600));

        when(transactionManager.execute(any())).thenAnswer(invocation -> {
            final Supplier<?> action = invocation.getArgument(0);
            return action.get();
        });
        lenient().when(configurationPolicyEngine.nextActionFor(any(), any())).thenReturn(null);
        lenient().when(configurationSetRepository.findByMintId(MINT_ID)).thenReturn(List.of(activeConfiguration));
        lenient().when(configurationSetRepository.findByRevision(MINT_ID, activeConfiguration.revisionId()))
            .thenReturn(Optional.of(activeConfiguration));
        lenient().when(mintRepository.findById(MINT_ID)).thenReturn(Optional.of(activeAggregate));
        lenient().when(mintAggregateViewRepository.findById(MINT_ID)).thenReturn(Optional.of(activeView));
        lenient().when(approvalRepository.findApprovals(any(), any())).thenAnswer(invocation -> {
            final MintId mintId = invocation.getArgument(0);
            final ConfigurationRevisionId revisionId = invocation.getArgument(1);
            return recordedApprovals.getOrDefault(mintId, Map.of())
                .getOrDefault(revisionId, List.of());
        });
        lenient().doAnswer(invocation -> {
            final MintId mintId = invocation.getArgument(0);
            final ApprovalRecord record = invocation.getArgument(1);
            recordedApprovals.computeIfAbsent(mintId, key -> new HashMap<>())
                .computeIfAbsent(record.revisionId(), key -> new ArrayList<>())
                .add(record);
            return null;
        }).when(approvalRepository).recordApproval(any(), any());

        interactor = new ManageConfigurationInteractor(configurationSetRepository, mintRepository,
            mintAggregateViewRepository, approvalRepository, configurationValidator, configurationPolicyEngine,
            notificationDispatcher, configurationSecretManager, eventPublisher, transactionManager, CLOCK);
    }

    @Test
    // Verifies that submitting a configuration revision persists it, converts secrets, and emits submission events.
    void shouldSubmitConfigurationRevision() {
        final ConfigurationRevisionId nextRevision = activeConfiguration.revisionId().next();
        when(configurationSecretManager.prepareValue(eq(MINT_ID), eq(nextRevision), eq("fee"), any()))
            .thenReturn(ConfigurationValue.ofPlainText("1.5"));
        when(configurationSecretManager.prepareValue(eq(MINT_ID), eq(nextRevision), eq("api.key"), any()))
            .thenReturn(ConfigurationValue.ofSecret(ConfigurationSecret.namedReference("secret:api.key")));

        final SubmitConfigurationCommand command = new SubmitConfigurationCommand(
            MINT_ID.asString(),
            OPERATOR_ID.toString(),
            new ConfigurationPayload(
                Map.of(
                    "fee", new ConfigurationValueInput("1.5", null, null, null),
                    "api.key", new ConfigurationValueInput(null, null, "plaintext secret", SecretMaterialization.VAULT_REFERENCE)
                ),
                Map.of("env", "test"),
                "Increase mint fee"
            ),
            "v1.1.0",
            List.of("ops-review"),
            List.of("INC-42"),
            List.of("security-check"),
            null,
            null
        );

        final ConfigurationWorkflowResponse response = interactor.submit(command);

        final ArgumentCaptor<ConfigurationSet> savedCaptor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(configurationSetRepository).save(eq(MINT_ID), savedCaptor.capture());
        final ConfigurationSet saved = savedCaptor.getValue();
        assertThat(saved.revisionId()).isEqualTo(nextRevision);
        assertThat(saved.state()).isEqualTo(ConfigurationRevisionState.DRAFT);
        assertThat(saved.parameters()).containsKeys("fee", "api.key");
        assertThat(saved.parameters().get("fee").resolvedValue()).isEqualTo("1.5");
        assertThat(saved.parameters().get("api.key").isSecret()).isTrue();

        final ArgumentCaptor<ConfigurationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ConfigurationLifecycleEvent.class);
        verify(eventPublisher).publish(eq(MINT_ID), eventCaptor.capture());
        verify(notificationDispatcher).dispatch(eq(MINT_ID), same(eventCaptor.getValue()), eq(saved));
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigurationSubmittedEvent.class);
        final ConfigurationSubmittedEvent submittedEvent = (ConfigurationSubmittedEvent) eventCaptor.getValue();
        assertThat(submittedEvent.diff().added()).containsKey("api.key");
        assertThat(submittedEvent.diff().changed()).containsKey("fee");

        assertThat(response.mintId()).isEqualTo(MINT_ID.asString());
        assertThat(response.requestedRevision()).isEqualTo(Long.toString(nextRevision.value()));
        assertThat(response.diff().added()).containsKey("api.key");
        assertThat(response.diff().changed()).containsKey("fee");
        assertThat(response.approvalChecklist().required()).containsExactly("security-check");
        assertThat(response.nextAction()).isEqualTo(NextAction.VALIDATE);
    }

    @Test
    // Ensures preview validation promotes the revision, records the report, and publishes validation events.
    void shouldValidateDraftRevisionAndBroadcastOutcome() {
        final ConfigurationRevisionId targetRevision = activeConfiguration.revisionId().next();
        final ConfigurationSet draftRevision = draftRevision(targetRevision, Map.of(
            "fee", ConfigurationValue.ofPlainText("1.5"),
            "description", ConfigurationValue.ofPlainText("Updated fee")
        ), NOW.minusSeconds(300));
        when(configurationSetRepository.findByRevision(MINT_ID, targetRevision)).thenReturn(Optional.of(draftRevision));

        final AuditMetadata validatorAudit = audit("validator", "Validated revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(200), targetRevision);
        final ValidationReport report = ValidationReport.success(targetRevision, validatorAudit,
            validatorAudit.timestamp(), Map.of("reportId", "VAL-%s".formatted(targetRevision.value())));
        when(configurationValidator.validate(MINT_ID, draftRevision)).thenReturn(report);

        final PreviewConfigurationCommand command = new PreviewConfigurationCommand(MINT_ID.asString(),
            OPERATOR_ID.toString(), Long.toString(targetRevision.value()), true, "v1.1.0", List.of(), List.of(), null,
            null);

        final ConfigurationWorkflowResponse response = interactor.preview(command);

        final ArgumentCaptor<ConfigurationSet> savedCaptor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(configurationSetRepository).save(eq(MINT_ID), savedCaptor.capture());
        final ConfigurationSet validatedRevision = savedCaptor.getValue();
        assertThat(validatedRevision.state()).isEqualTo(ConfigurationRevisionState.VALIDATED);
        assertThat(validatedRevision.validationReport()).isEqualTo(report);

        final ArgumentCaptor<ConfigurationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ConfigurationLifecycleEvent.class);
        verify(eventPublisher).publish(eq(MINT_ID), eventCaptor.capture());
        verify(notificationDispatcher).dispatch(eq(MINT_ID), same(eventCaptor.getValue()), eq(validatedRevision));
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigurationValidatedEvent.class);

        assertThat(response.state()).isEqualTo(ConfigurationRevisionState.VALIDATED);
        assertThat(response.validation().executed()).isTrue();
        assertThat(response.validation().valid()).isTrue();
        assertThat(response.validation().validator()).isEqualTo(validatorAudit.actor());
        assertThat(response.validation().issues()).isEmpty();
        assertThat(response.validation().artefacts()).containsEntry("reportId", "VAL-%s".formatted(targetRevision.value()));
    }

    @Test
    // Confirms validation failures raise the domain exception while persisting the failure state and emitting events.
    void shouldSurfaceValidationFailuresWithReport() {
        final ConfigurationRevisionId targetRevision = activeConfiguration.revisionId().next();
        final ConfigurationSet draftRevision = draftRevision(targetRevision,
            Map.of("fee", ConfigurationValue.ofPlainText("1.5")), NOW.minusSeconds(400));
        when(configurationSetRepository.findByRevision(MINT_ID, targetRevision)).thenReturn(Optional.of(draftRevision));

        final AuditMetadata validatorAudit = audit("validator", "Rejected revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(350), targetRevision);
        final ValidationReport failureReport = ValidationReport.failure(targetRevision,
            List.of("Threshold too high"), validatorAudit, validatorAudit.timestamp(), Map.of("reportId", "VAL-ERR"));
        when(configurationValidator.validate(MINT_ID, draftRevision)).thenReturn(failureReport);

        final PreviewConfigurationCommand command = new PreviewConfigurationCommand(MINT_ID.asString(),
            OPERATOR_ID.toString(), Long.toString(targetRevision.value()), true, "v1.1.0", List.of(), List.of(), null,
            null);

        final Throwable thrown = catchThrowable(() -> interactor.preview(command));
        assertThat(thrown).isInstanceOf(ConfigurationValidationException.class);
        final ConfigurationValidationException exception = (ConfigurationValidationException) thrown;
        assertThat(exception.report()).isEqualTo(failureReport);
        assertThat(exception.response().validation().valid()).isFalse();
        assertThat(exception.response().validation().issues()).contains("Threshold too high");

        final ArgumentCaptor<ConfigurationSet> savedCaptor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(configurationSetRepository).save(eq(MINT_ID), savedCaptor.capture());
        assertThat(savedCaptor.getValue().state()).isEqualTo(ConfigurationRevisionState.REJECTED);

        final ArgumentCaptor<ConfigurationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ConfigurationLifecycleEvent.class);
        verify(eventPublisher).publish(eq(MINT_ID), eventCaptor.capture());
        verify(notificationDispatcher).dispatch(eq(MINT_ID), same(eventCaptor.getValue()), eq(savedCaptor.getValue()));
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigurationValidationFailedEvent.class);
    }

    @Test
    // Validates that approving a revision stores the approval, emits events, and updates the checklist summaries.
    void shouldApproveValidatedRevisionAndRecordAudit() {
        final ConfigurationRevisionId targetRevision = activeConfiguration.revisionId().next();
        final ConfigurationSet draftRevision = draftRevision(targetRevision,
            Map.of("fee", ConfigurationValue.ofPlainText("1.6")), NOW.minusSeconds(500));
        final AuditMetadata validatorAudit = audit("validator", "Validated revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(450), targetRevision);
        final ValidationReport report = ValidationReport.success(targetRevision, validatorAudit,
            validatorAudit.timestamp(), Map.of());
        final ConfigurationSet validatedRevision = draftRevision.recordValidation(report).configurationSet();
        when(configurationSetRepository.findByRevision(MINT_ID, targetRevision))
            .thenReturn(Optional.of(validatedRevision));

        final ReviewConfigurationCommand command = new ReviewConfigurationCommand(MINT_ID.asString(),
            OPERATOR_ID.toString(), Long.toString(targetRevision.value()), ReviewDecision.APPROVE,
            List.of("ops", "security"), List.of(), "v1.1.0", List.of(), List.of(), null, null);

        final ConfigurationWorkflowResponse response = interactor.review(command);

        final ArgumentCaptor<ConfigurationSet> savedCaptor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(configurationSetRepository).save(eq(MINT_ID), savedCaptor.capture());
        final ConfigurationSet approvedRevision = savedCaptor.getValue();
        assertThat(approvedRevision.state()).isEqualTo(ConfigurationRevisionState.APPROVED);
        assertThat(approvedRevision.approvalRecord()).isNotNull();

        final ArgumentCaptor<ApprovalRecord> approvalCaptor = ArgumentCaptor.forClass(ApprovalRecord.class);
        verify(approvalRepository).recordApproval(eq(MINT_ID), approvalCaptor.capture());
        final ApprovalRecord recorded = approvalCaptor.getValue();
        assertThat(recorded.revisionId()).isEqualTo(targetRevision);
        assertThat(recorded.conditions()).containsExactlyInAnyOrder("ops", "security");

        final ArgumentCaptor<ConfigurationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ConfigurationLifecycleEvent.class);
        verify(eventPublisher).publish(eq(MINT_ID), eventCaptor.capture());
        verify(notificationDispatcher).dispatch(eq(MINT_ID), same(eventCaptor.getValue()), eq(approvedRevision));
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigurationApprovedEvent.class);

        assertThat(response.approval().approved()).isTrue();
        assertThat(response.approval().approver()).isEqualTo(OPERATOR_ID.toString());
        assertThat(response.approvalChecklist().satisfied()).containsExactlyInAnyOrder("ops", "security");
        assertThat(response.approvalHistory()).hasSize(1);
        assertThat(response.approvalHistory().getFirst().approver()).isEqualTo(OPERATOR_ID.toString());
    }

    @Test
    // Checks that rejecting a revision records the failure report and returns sanitized rejection reasons.
    void shouldRejectRevisionAndExposeFailureDetails() {
        final ConfigurationRevisionId targetRevision = activeConfiguration.revisionId().next();
        final ConfigurationSet draftRevision = draftRevision(targetRevision,
            Map.of("fee", ConfigurationValue.ofPlainText("1.4")), NOW.minusSeconds(520));
        final AuditMetadata validatorAudit = audit("validator", "Validated revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(480), targetRevision);
        final ValidationReport report = ValidationReport.success(targetRevision, validatorAudit,
            validatorAudit.timestamp(), Map.of());
        final ConfigurationSet validatedRevision = draftRevision.recordValidation(report).configurationSet();
        when(configurationSetRepository.findByRevision(MINT_ID, targetRevision))
            .thenReturn(Optional.of(validatedRevision));

        final ReviewConfigurationCommand command = new ReviewConfigurationCommand(MINT_ID.asString(),
            OPERATOR_ID.toString(), Long.toString(targetRevision.value()), ReviewDecision.REJECT,
            List.of("ops"), List.of("  missing qa sign-off  "), "v1.1.0", List.of(), List.of(), null, null);

        final ConfigurationWorkflowResponse response = interactor.review(command);

        final ArgumentCaptor<ConfigurationSet> savedCaptor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(configurationSetRepository).save(eq(MINT_ID), savedCaptor.capture());
        final ConfigurationSet rejectedRevision = savedCaptor.getValue();
        assertThat(rejectedRevision.state()).isEqualTo(ConfigurationRevisionState.REJECTED);
        assertThat(rejectedRevision.validationReport()).isNotNull();
        assertThat(rejectedRevision.validationReport().valid()).isFalse();

        final ArgumentCaptor<ConfigurationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ConfigurationLifecycleEvent.class);
        verify(eventPublisher).publish(eq(MINT_ID), eventCaptor.capture());
        verify(notificationDispatcher).dispatch(eq(MINT_ID), same(eventCaptor.getValue()), eq(rejectedRevision));
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigurationValidationFailedEvent.class);

        assertThat(response.validation().valid()).isFalse();
        assertThat(response.validation().issues()).containsExactly("missing qa sign-off");
        assertThat(response.approval().approved()).isFalse();
        assertThat(response.approval().decision()).isEqualTo(ReviewDecision.REJECT);
    }

    @Test
    // Demonstrates that applying an approved revision updates the aggregate, preserves secrets, and emits lifecycle events.
    void shouldApplyApprovedRevisionAndUpdateAggregate() {
        final ConfigurationRevisionId targetRevision = activeConfiguration.revisionId().next();
        final ConfigurationSet draftRevision = draftRevision(targetRevision, Map.of(
            "fee", ConfigurationValue.ofPlainText("1.7"),
            "api.key", ConfigurationValue.ofSecret(ConfigurationSecret.namedReference("secret:api.key"))
        ), NOW.minusSeconds(800));
        final AuditMetadata validatorAudit = audit("validator", "Validated revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(760), targetRevision);
        final ValidationReport report = ValidationReport.success(targetRevision, validatorAudit,
            validatorAudit.timestamp(), Map.of());
        final ConfigurationSet validatedRevision = draftRevision.recordValidation(report).configurationSet();
        final AuditMetadata approvalAudit = audit("approver", "Approved revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(720), targetRevision);
        final ApprovalRecord approvalRecord = new ApprovalRecord(targetRevision, approvalAudit, approvalAudit.timestamp(),
            List.of("ops"), NotificationPolicySnapshot.fromPolicy(notificationPolicy));
        final ConfigurationSet approvedRevision = validatedRevision.approve(approvalRecord).configurationSet();
        when(configurationSetRepository.findByRevision(MINT_ID, targetRevision))
            .thenReturn(Optional.of(approvedRevision));

        final ApplyConfigurationCommand command = new ApplyConfigurationCommand(MINT_ID.asString(),
            OPERATOR_ID.toString(), Long.toString(targetRevision.value()), "DEP-100", "v1.1.0",
            List.of("deployment"), List.of("INC-42"), null, null);

        final ConfigurationWorkflowResponse response = interactor.apply(command);

        final ArgumentCaptor<ConfigurationSet> savedCaptor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(configurationSetRepository).save(eq(MINT_ID), savedCaptor.capture());
        final ConfigurationSet appliedRevision = savedCaptor.getValue();
        assertThat(appliedRevision.state()).isEqualTo(ConfigurationRevisionState.APPLIED);
        assertThat(appliedRevision.parameters().get("api.key").isSecret()).isTrue();

        final ArgumentCaptor<MintAggregate> aggregateCaptor = ArgumentCaptor.forClass(MintAggregate.class);
        verify(mintRepository).save(aggregateCaptor.capture());
        assertThat(aggregateCaptor.getValue().configurationSet().revisionId()).isEqualTo(targetRevision);

        final ArgumentCaptor<ConfigurationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ConfigurationLifecycleEvent.class);
        verify(eventPublisher).publish(eq(MINT_ID), eventCaptor.capture());
        verify(notificationDispatcher).dispatch(eq(MINT_ID), same(eventCaptor.getValue()), eq(appliedRevision));
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigurationAppliedEvent.class);
        final ConfigurationAppliedEvent appliedEvent = (ConfigurationAppliedEvent) eventCaptor.getValue();
        assertThat(appliedEvent.diff().changed()).containsKey("fee");

        assertThat(response.state()).isEqualTo(ConfigurationRevisionState.APPLIED);
        assertThat(response.requestedConfiguration().values().get("api.key").secret()).isTrue();
        assertThat(response.diffSummary().changed()).isEqualTo(1);
    }

    @Test
    // Confirms that rolling back to a prior applied revision emits a rollback event and returns audit metadata.
    void shouldRollbackToPreviouslyAppliedRevision() {
        final ConfigurationSet previousApplied = appliedConfiguration(activeConfiguration, ConfigurationRevisionId.of(2), Map.of(
            "fee", ConfigurationValue.ofPlainText("1.4"),
            "api.key", ConfigurationValue.ofSecret(ConfigurationSecret.namedReference("secret:api.key"))
        ), NOW.minusSeconds(1_200));
        final ConfigurationSet activeApplied = appliedConfiguration(previousApplied, ConfigurationRevisionId.of(3), Map.of(
            "fee", ConfigurationValue.ofPlainText("1.8"),
            "api.key", ConfigurationValue.ofSecret(ConfigurationSecret.namedReference("secret:api.key"))
        ), NOW.minusSeconds(900));
        final MintAggregate aggregate = mintAggregate(activeApplied);
        when(mintRepository.findById(MINT_ID)).thenReturn(Optional.of(aggregate));
        when(mintAggregateViewRepository.findById(MINT_ID)).thenReturn(Optional.of(new MintAggregateView(MINT_ID,
            aggregate.lifecycleState().value(), activeApplied.revisionId(), "v1.2.0", NOW.minusSeconds(300))));
        when(configurationSetRepository.findByRevision(MINT_ID, previousApplied.revisionId()))
            .thenReturn(Optional.of(previousApplied));

        final RollbackConfigurationCommand command = new RollbackConfigurationCommand(MINT_ID.asString(),
            OPERATOR_ID.toString(), Long.toString(previousApplied.revisionId().value()), "Rollback drift",
            "AUD-12", "v1.2.0", List.of("rollback"), List.of("INC-77"), null, null);

        final ConfigurationWorkflowResponse response = interactor.rollback(command);

        final ArgumentCaptor<ConfigurationSet> savedCaptor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(configurationSetRepository).save(eq(MINT_ID), savedCaptor.capture());
        final ConfigurationSet rolledBack = savedCaptor.getValue();
        assertThat(rolledBack.state()).isEqualTo(ConfigurationRevisionState.ROLLED_BACK);

        final ArgumentCaptor<ConfigurationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ConfigurationLifecycleEvent.class);
        verify(eventPublisher).publish(eq(MINT_ID), eventCaptor.capture());
        verify(notificationDispatcher).dispatch(eq(MINT_ID), same(eventCaptor.getValue()), eq(rolledBack));
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigurationRollbackEvent.class);
        final ConfigurationRollbackEvent rollbackEvent = (ConfigurationRollbackEvent) eventCaptor.getValue();
        assertThat(rollbackEvent.revisionId()).isEqualTo(activeApplied.revisionId());
        assertThat(rollbackEvent.targetRevisionId()).isEqualTo(previousApplied.revisionId());

        assertThat(response.rollback()).isNotNull();
        assertThat(response.rollback().sourceRevision()).isEqualTo(Long.toString(activeApplied.revisionId().value()));
        assertThat(response.rollback().targetRevision()).isEqualTo(Long.toString(previousApplied.revisionId().value()));
    }

    @Test
    // Ensures rollback guardrails propagate as RollbackConflictException when domain preconditions are violated.
    void shouldWrapDomainConflictsDuringRollback() {
        final ConfigurationSet activeApplied = appliedConfiguration(activeConfiguration, ConfigurationRevisionId.of(4), Map.of(
            "fee", ConfigurationValue.ofPlainText("2.0")
        ), NOW.minusSeconds(700));
        final MintAggregate aggregate = mintAggregate(activeApplied);
        when(mintRepository.findById(MINT_ID)).thenReturn(Optional.of(aggregate));
        final ConfigurationRevisionId targetRevision = ConfigurationRevisionId.of(2);
        final ConfigurationSet draft = draftRevision(targetRevision,
            Map.of("fee", ConfigurationValue.ofPlainText("1.1")), NOW.minusSeconds(900));
        final AuditMetadata validatorAudit = audit("validator", "Validated revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(880), targetRevision);
        final ValidationReport report = ValidationReport.success(targetRevision, validatorAudit,
            validatorAudit.timestamp(), Map.of());
        final ConfigurationSet validated = draft.recordValidation(report).configurationSet();
        final AuditMetadata approvalAudit = audit("approver", "Approved revision %s".formatted(targetRevision.value()),
            NOW.minusSeconds(860), targetRevision);
        final ApprovalRecord approvalRecord = new ApprovalRecord(targetRevision, approvalAudit, approvalAudit.timestamp(),
            List.of(), NotificationPolicySnapshot.fromPolicy(notificationPolicy));
        final ConfigurationSet approvedOnly = validated.approve(approvalRecord).configurationSet();
        when(configurationSetRepository.findByRevision(MINT_ID, targetRevision)).thenReturn(Optional.of(approvedOnly));

        final RollbackConfigurationCommand command = new RollbackConfigurationCommand(MINT_ID.asString(),
            OPERATOR_ID.toString(), Long.toString(targetRevision.value()), "Rollback to bootstrap",
            null, "v1.0.0", List.of(), List.of(), null, null);

        assertThatThrownBy(() -> interactor.rollback(command))
            .isInstanceOf(RollbackConflictException.class)
            .hasMessageContaining("never applied");

        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(notificationDispatcher);
        verify(configurationSetRepository, never()).save(any(), any());
    }

    private ConfigurationSet draftRevision(final ConfigurationRevisionId revisionId,
                                           final Map<String, ConfigurationValue> parameters,
                                           final Instant submittedAt) {
        final AuditMetadata submissionAudit = audit("operator", "Submitted revision %s".formatted(revisionId.value()),
            submittedAt, revisionId);
        return new ConfigurationSet(revisionId, parameters, submissionAudit, ConfigurationRevisionState.DRAFT,
            ConfigurationRevisionHistory.initial(ConfigurationRevisionState.DRAFT, submissionAudit), null, null);
    }

    private ConfigurationSet initialAppliedConfiguration(final ConfigurationRevisionId revisionId) {
        final AuditMetadata auditMetadata = audit("bootstrap", "Applied initial configuration", NOW.minusSeconds(3_000),
            revisionId);
        final ConfigurationRevisionHistory history = ConfigurationRevisionHistory
            .initial(ConfigurationRevisionState.DRAFT, auditMetadata)
            .append(ConfigurationRevisionState.VALIDATED, auditMetadata)
            .append(ConfigurationRevisionState.APPROVED, auditMetadata)
            .append(ConfigurationRevisionState.APPLIED, auditMetadata);
        final ValidationReport report = ValidationReport.success(revisionId, auditMetadata,
            auditMetadata.timestamp(), Map.of());
        final ApprovalRecord approvalRecord = new ApprovalRecord(revisionId, auditMetadata, auditMetadata.timestamp(),
            List.of(), NotificationPolicySnapshot.fromPolicy(notificationPolicy));
        return new ConfigurationSet(revisionId, Map.of("fee", ConfigurationValue.ofPlainText("0.5")), auditMetadata,
            ConfigurationRevisionState.APPLIED, history, report, approvalRecord);
    }

    private ConfigurationSet appliedConfiguration(final ConfigurationSet currentActive,
                                                  final ConfigurationRevisionId revisionId,
                                                  final Map<String, ConfigurationValue> parameters,
                                                  final Instant appliedAt) {
        final AuditMetadata submissionAudit = audit("operator", "Submitted revision %s".formatted(revisionId.value()),
            appliedAt.minusSeconds(60), revisionId);
        final ConfigurationSet draft = new ConfigurationSet(revisionId, parameters, submissionAudit,
            ConfigurationRevisionState.DRAFT,
            ConfigurationRevisionHistory.initial(ConfigurationRevisionState.DRAFT, submissionAudit), null, null);
        final AuditMetadata validatorAudit = audit("validator", "Validated revision %s".formatted(revisionId.value()),
            appliedAt.minusSeconds(40), revisionId);
        final ValidationReport report = ValidationReport.success(revisionId, validatorAudit,
            validatorAudit.timestamp(), Map.of());
        final ConfigurationSet validated = draft.recordValidation(report).configurationSet();
        final AuditMetadata approvalAudit = audit("approver", "Approved revision %s".formatted(revisionId.value()),
            appliedAt.minusSeconds(20), revisionId);
        final ApprovalRecord approvalRecord = new ApprovalRecord(revisionId, approvalAudit, approvalAudit.timestamp(),
            List.of(), NotificationPolicySnapshot.fromPolicy(notificationPolicy));
        final ConfigurationSet approved = validated.approve(approvalRecord).configurationSet();
        final AuditMetadata applyAudit = audit("operator", "Applied revision %s".formatted(revisionId.value()), appliedAt,
            revisionId);
        return approved.apply(currentActive, applyAudit).configurationSet();
    }

    private AuditMetadata audit(final String actor,
                                final String action,
                                final Instant timestamp,
                                final ConfigurationRevisionId revisionId) {
        return new AuditMetadata(actor, action, timestamp).withLifecycleContext(revisionId, notificationPolicy);
    }

    private MintAggregate mintAggregate(final ConfigurationSet configurationSet) {
        final AuditMetadata aggregateAudit = configurationSet.auditMetadata();
        final OperatorAccount operator = new OperatorAccount(OPERATOR_ID, "Primary Operator", Set.of("ADMIN"),
            aggregateAudit);
        final AuditTrail trail = AuditTrail.create(aggregateAudit);
        return MintAggregate.reconstitute(MINT_ID, LifecycleState.provisioned(), configurationSet, operator,
            notificationPolicy, trail, aggregateAudit);
    }
}
