package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.LinkedHashMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationValueInput;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ManageConfigurationRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ManageConfigurationResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.NextAction;
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
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationValue;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;
import xyz.tcheeric.cashu.mint.admin.domain.ValidationReport;

class ManageConfigurationInteractorTest {

    private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MintId MINT_ID = MintId.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID OPERATOR_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

    private RecordingConfigurationSetRepository configurationSetRepository;
    private RecordingMintRepository mintRepository;
    private RecordingMintViewRepository mintViewRepository;
    private RecordingApprovalRepository approvalRepository;
    private StubConfigurationValidator configurationValidator;
    private StubPolicyEngine policyEngine;
    private RecordingNotificationDispatcher notificationDispatcher;
    private RecordingSecretManager secretManager;
    private RecordingEventPublisher eventPublisher;
    private TransactionManager transactionManager;
    private ManageConfigurationInteractor interactor;
    private MintAggregate aggregate;
    private ConfigurationSet initialConfiguration;

    @BeforeEach
    void setUp() {
        configurationSetRepository = new RecordingConfigurationSetRepository();
        mintRepository = new RecordingMintRepository();
        mintViewRepository = new RecordingMintViewRepository();
        approvalRepository = new RecordingApprovalRepository();
        configurationValidator = new StubConfigurationValidator();
        policyEngine = new StubPolicyEngine();
        notificationDispatcher = new RecordingNotificationDispatcher();
        secretManager = new RecordingSecretManager();
        eventPublisher = new RecordingEventPublisher();
        transactionManager = new ImmediateTransactionManager();

        initialConfiguration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("version.tag", "v1"), new AuditMetadata("creator", "Mint created", NOW));

        final OperatorAccount operatorAccount = new OperatorAccount(OPERATOR_ID, "operator", Set.of("ADMIN"),
            new AuditMetadata("creator", "Mint created", NOW));
        final NotificationPolicy notificationPolicy =
            new NotificationPolicy(true, false, Duration.ofMinutes(5), new AuditMetadata("creator", "Mint created", NOW));
        aggregate = MintAggregate.create(MINT_ID, initialConfiguration, operatorAccount, notificationPolicy,
            new AuditMetadata("creator", "Mint created", NOW));

        configurationSetRepository.save(MINT_ID, initialConfiguration);
        mintRepository.save(aggregate);
        mintViewRepository.save(new MintAggregateView(MINT_ID, aggregate.lifecycleState().value(),
            initialConfiguration.revisionId(), "v1", NOW));

        interactor = new ManageConfigurationInteractor(configurationSetRepository, mintRepository,
            mintViewRepository, approvalRepository, configurationValidator, policyEngine, notificationDispatcher,
            secretManager, eventPublisher, transactionManager, CLOCK);
    }

    @Test
    // Ensures submit command stores a new configuration revision and emits diff artefacts.
    void shouldSubmitConfigurationRevision() {
        final ManageConfigurationRequest request = request(ConfigurationCommand.SUBMIT, null,
            Map.of("fee", new ConfigurationValueInput("0.5", null, null, null)));

        final ManageConfigurationResponse response = interactor.handle(request);

        assertThat(configurationSetRepository.latestRevision(MINT_ID).revisionId().value()).isEqualTo(2);
        assertThat(eventPublisher.publishedEvents).hasSize(1);
        assertThat(notificationDispatcher.dispatchedEvents).hasSize(1);
        assertThat(response.diff().added()).containsKey("fee");
        assertThat(response.nextAction()).isEqualTo(NextAction.VALIDATE);
    }

    @Test
    // Ensures validation failures trigger a domain-specific exception and surface the report.
    void shouldThrowValidationExceptionWhenValidationFails() {
        final ManageConfigurationResponse submission = interactor.handle(request(ConfigurationCommand.SUBMIT, null,
            Map.of("fee", new ConfigurationValueInput("0.6", null, null, null))));
        configurationValidator.nextReport = ValidationReport.failure(ConfigurationRevisionId.of(2), List.of("invalid"),
            new AuditMetadata("validator", "Validate", NOW.plusSeconds(5)), NOW.plusSeconds(5), Map.of());

        assertThatThrownBy(() -> interactor.handle(request(ConfigurationCommand.VALIDATE,
            submission.requestedRevision(), Map.of())))
            .isInstanceOf(ConfigurationValidationException.class)
            .extracting("report").extracting("valid").isEqualTo(false);
    }

    @Test
    // Ensures an approved revision records approval history and updates state.
    void shouldApproveConfigurationRevision() {
        interactor.handle(request(ConfigurationCommand.SUBMIT, null,
            Map.of("fee", new ConfigurationValueInput("0.6", null, null, null))));
        configurationValidator.nextReport = ValidationReport.success(ConfigurationRevisionId.of(2),
            new AuditMetadata("validator", "Validate", NOW.plusSeconds(5)), NOW.plusSeconds(5), Map.of());
        interactor.handle(request(ConfigurationCommand.VALIDATE, "2", Map.of()));

        final ManageConfigurationResponse response = interactor.handle(request(ConfigurationCommand.APPROVE, "2", Map.of()));

        assertThat(response.approval().approved()).isTrue();
        assertThat(approvalRepository.findApprovals(MINT_ID, ConfigurationRevisionId.of(2))).hasSize(1);
        assertThat(configurationSetRepository.findByRevision(MINT_ID, ConfigurationRevisionId.of(2)).orElseThrow().state())
            .isEqualTo(ConfigurationRevisionState.APPROVED);
    }

    @Test
    // Ensures applying an approved revision updates the aggregate and emits lifecycle notifications.
    void shouldApplyApprovedConfiguration() {
        interactor.handle(request(ConfigurationCommand.SUBMIT, null,
            Map.of("fee", new ConfigurationValueInput("0.7", null, null, null))));
        configurationValidator.nextReport = ValidationReport.success(ConfigurationRevisionId.of(2),
            new AuditMetadata("validator", "Validate", NOW.plusSeconds(5)), NOW.plusSeconds(5), Map.of());
        interactor.handle(request(ConfigurationCommand.VALIDATE, "2", Map.of()));
        interactor.handle(request(ConfigurationCommand.APPROVE, "2", Map.of()));

        final ManageConfigurationResponse response = interactor.handle(request(ConfigurationCommand.APPLY, "2", Map.of()));

        assertThat(response.state()).isEqualTo(ConfigurationRevisionState.APPLIED);
        assertThat(mintRepository.findById(MINT_ID).orElseThrow().configurationSet().revisionId().value()).isEqualTo(2);
        assertThat(eventPublisher.publishedEvents).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    // Ensures applying without approval surfaces a MissingApprovalException.
    void shouldRejectApplyWithoutApproval() {
        interactor.handle(request(ConfigurationCommand.SUBMIT, null,
            Map.of("fee", new ConfigurationValueInput("0.8", null, null, null))));

        assertThatThrownBy(() -> interactor.handle(request(ConfigurationCommand.APPLY, "2", Map.of())))
            .isInstanceOf(MissingApprovalException.class);
    }

    @Test
    // Ensures rollback conflicts are wrapped in a domain-specific exception.
    void shouldWrapRollbackConflicts() {
        final ConfigurationSet draft = new ConfigurationSet(ConfigurationRevisionId.of(3),
            Map.of("fee", "0.4"), new AuditMetadata("creator", "Create", NOW));
        configurationSetRepository.save(MINT_ID, draft);

        assertThatThrownBy(() -> interactor.handle(request(ConfigurationCommand.ROLLBACK, "3", Map.of())))
            .isInstanceOf(RollbackConflictException.class);
    }

    private ManageConfigurationRequest request(final ConfigurationCommand command,
                                               final String revision,
                                               final Map<String, ConfigurationValueInput> parameters) {
        final Map<String, ConfigurationValueInput> payload = parameters == null ? Map.of() : parameters;
        return new ManageConfigurationRequest(MINT_ID.asString(), OPERATOR_ID.toString(), revision, command, "v-next",
            payload, List.of(), List.of(), List.of(), null, null);
    }

    private static final class RecordingConfigurationSetRepository implements ConfigurationSetRepository {

        private final Map<MintId, Map<ConfigurationRevisionId, ConfigurationSet>> store = new HashMap<>();

        @Override
        public void save(final MintId mintId, final ConfigurationSet configurationSet) {
            store.computeIfAbsent(mintId, key -> new LinkedHashMap<>())
                .put(configurationSet.revisionId(), configurationSet);
        }

        @Override
        public Optional<ConfigurationSet> findByRevision(final MintId mintId, final ConfigurationRevisionId revisionId) {
            return Optional.ofNullable(store.getOrDefault(mintId, Map.of()).get(revisionId));
        }

        @Override
        public List<ConfigurationSet> findByMintId(final MintId mintId) {
            return store.getOrDefault(mintId, Map.of()).values().stream()
                .sorted((a, b) -> Long.compare(a.revisionId().value(), b.revisionId().value()))
                .toList();
        }

        ConfigurationSet latestRevision(final MintId mintId) {
            return findByMintId(mintId).getLast();
        }
    }

    private static final class RecordingMintRepository implements MintRepository {

        private final Map<MintId, MintAggregate> store = new HashMap<>();

        @Override
        public void save(final MintAggregate aggregate) {
            store.put(aggregate.mintId(), aggregate);
        }

        @Override
        public Optional<MintAggregate> findById(final MintId mintId) {
            return Optional.ofNullable(store.get(mintId));
        }

        @Override
        public List<MintAggregate> findAll() {
            return new ArrayList<>(store.values());
        }
    }

    private static final class RecordingMintViewRepository implements MintAggregateViewRepository {

        private final Map<MintId, MintAggregateView> store = new HashMap<>();

        @Override
        public void upsert(final xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent event) { }

        @Override
        public Optional<MintAggregateView> findById(final MintId mintId) {
            return Optional.ofNullable(store.get(mintId));
        }

        @Override
        public List<MintAggregateView> findAll() {
            return new ArrayList<>(store.values());
        }

        void save(final MintAggregateView view) {
            store.put(view.mintId(), view);
        }
    }

    private static final class RecordingApprovalRepository implements ConfigurationApprovalRepository {

        private final Map<MintId, Map<ConfigurationRevisionId, List<ApprovalRecord>>> approvals = new HashMap<>();

        @Override
        public void recordApproval(final MintId mintId, final ApprovalRecord approvalRecord) {
            approvals.computeIfAbsent(mintId, key -> new HashMap<>())
                .computeIfAbsent(approvalRecord.revisionId(), key -> new ArrayList<>())
                .add(approvalRecord);
        }

        @Override
        public List<ApprovalRecord> findApprovals(final MintId mintId, final ConfigurationRevisionId revisionId) {
            return approvals.getOrDefault(mintId, Map.of())
                .getOrDefault(revisionId, List.of());
        }
    }

    private static final class StubConfigurationValidator implements ConfigurationValidator {

        private ValidationReport nextReport;

        @Override
        public ValidationReport validate(final MintId mintId, final ConfigurationSet configurationSet) {
            if (nextReport != null) {
                return nextReport;
            }
            return ValidationReport.success(configurationSet.revisionId(),
                new AuditMetadata("validator", "Validate", NOW.plusSeconds(5)), NOW.plusSeconds(5), Map.of());
        }
    }

    private static final class StubPolicyEngine implements ConfigurationPolicyEngine {

        @Override
        public NextAction nextActionFor(final MintId mintId, final ConfigurationSet configurationSet) {
            return null;
        }
    }

    private static final class RecordingNotificationDispatcher implements ConfigurationNotificationDispatcher {

        private final List<ConfigurationLifecycleEvent> dispatchedEvents = new ArrayList<>();

        @Override
        public void dispatch(final MintId mintId,
                             final ConfigurationLifecycleEvent event,
                             final ConfigurationSet configurationSet) {
            dispatchedEvents.add(event);
        }
    }

    private static final class RecordingSecretManager implements ConfigurationSecretManager {

        @Override
        public ConfigurationValue prepareValue(final MintId mintId,
                                               final ConfigurationRevisionId revisionId,
                                               final String parameterKey,
                                               final ConfigurationValueInput input) {
            if (input.secretPlaintext() != null) {
                return ConfigurationValue.ofPlainText("secret:" + parameterKey);
            }
            if (input.secretReference() != null) {
                return ConfigurationValue.ofPlainText(input.secretReference());
            }
            return ConfigurationValue.ofPlainText(input.value());
        }
    }

    private static final class RecordingEventPublisher implements ConfigurationLifecycleEventPublisher {

        private final List<ConfigurationLifecycleEvent> publishedEvents = new ArrayList<>();

        @Override
        public void publish(final MintId mintId, final ConfigurationLifecycleEvent event) {
            publishedEvents.add(event);
        }
    }

    private static final class ImmediateTransactionManager implements TransactionManager {

        @Override
        public <T> T execute(final Supplier<T> action) {
            return action.get();
        }
    }
}
