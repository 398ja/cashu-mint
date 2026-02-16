package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.ManageMintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.ManageMintLifecycleResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.LifecycleCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent.MintLifecycleEventType;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;

class ManageMintLifecycleInteractorTest {

    private static final String MINT_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174001";
    private static final String VERSION_TAG = "v1";
    private static final String REQUEST_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String CORRELATION_ID = "maintenance-window";

    private RecordingMintRepository mintRepository;
    private RecordingConfigurationSetRepository configurationSetRepository;
    private RecordingTransactionManager transactionManager;
    private RecordingEventPublisher eventPublisher;
    private Clock clock;
    private ManageMintLifecycleInteractor interactor;

    @BeforeEach
    void setUp() {
        mintRepository = new RecordingMintRepository();
        configurationSetRepository = new RecordingConfigurationSetRepository();
        transactionManager = new RecordingTransactionManager();
        eventPublisher = new RecordingEventPublisher();
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        interactor = new ManageMintLifecycleInteractor(mintRepository, configurationSetRepository, transactionManager,
            eventPublisher, clock);
    }

    @Test
    // Ensures a mint can be created, saved, and emits a creation event.
    void shouldCreateMintAndEmitEvent() {
        final ManageMintLifecycleRequest request = request(LifecycleCommand.CREATE, VERSION_TAG);

        final ManageMintLifecycleResponse response = interactor.handle(request);

        assertEquals(LifecycleState.State.PROVISIONING, response.lifecycleState());
        assertEquals(VERSION_TAG, response.versionTag());
        assertEquals(1, transactionManager.executionCount);
        assertNotNull(mintRepository.lastSaved);
        assertEquals(LifecycleState.State.PROVISIONING, mintRepository.lastSaved.lifecycleState().value());
        assertEquals(1, configurationSetRepository.countRevisions(MintId.fromString(MINT_ID)));
        assertEquals(1, eventPublisher.events.size());
        final MintLifecycleEvent event = eventPublisher.events.getFirst();
        assertEquals(MintLifecycleEventType.CREATED, event.type());
        assertEquals(MintId.fromString(MINT_ID), event.mintId());
        assertEquals(ConfigurationRevisionId.of(1), event.configurationRevisionId());
        assertEquals(clock.instant(), event.auditMetadata().timestamp());
    }

    @Test
    // Ensures creating a mint fails when an aggregate already exists.
    void shouldRejectDuplicateMintCreation() {
        storeProvisionedMint("existing");

        final ManageMintLifecycleRequest request = request(LifecycleCommand.CREATE, "duplicate");

        assertThrows(IllegalStateException.class, () -> interactor.handle(request));
        assertEquals(1, transactionManager.executionCount);
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    // Ensures configuration updates advance the revision and publish an event.
    void shouldUpdateConfigurationAndEmitEvent() {
        storeProvisionedMint("initial");

        final ManageMintLifecycleRequest request = request(LifecycleCommand.UPDATE_CONFIGURATION, "next");

        final ManageMintLifecycleResponse response = interactor.handle(request);

        assertEquals(LifecycleState.State.PROVISIONING, response.lifecycleState());
        assertEquals("next", mintRepository.lastSaved.configurationSet().parameters().get("version.tag"));
        assertEquals(ConfigurationRevisionId.of(2), mintRepository.lastSaved.configurationSet().revisionId());
        assertEquals(1, configurationSetRepository.countRevisions(MintId.fromString(MINT_ID), ConfigurationRevisionId.of(2)));
        final MintLifecycleEvent event = eventPublisher.events.getLast();
        assertEquals(MintLifecycleEventType.CONFIGURATION_UPDATED, event.type());
        assertEquals("next", event.versionTag());
    }

    @Test
    // Ensures configuration updates fail when the mint aggregate is missing.
    void shouldRejectConfigurationUpdateForMissingMint() {
        final ManageMintLifecycleRequest request = request(LifecycleCommand.UPDATE_CONFIGURATION, "stale");

        assertThrows(IllegalStateException.class, () -> interactor.handle(request));
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    // Ensures a mint can be paused from an active state and emits the correct event.
    void shouldPauseMintAndEmitEvent() {
        storeActiveMint();

        final ManageMintLifecycleRequest request = request(LifecycleCommand.PAUSE, "pause-tag");

        final ManageMintLifecycleResponse response = interactor.handle(request);

        assertEquals(LifecycleState.State.SUSPENDED, response.lifecycleState());
        final MintLifecycleEvent event = eventPublisher.events.getLast();
        assertEquals(MintLifecycleEventType.PAUSED, event.type());
        assertEquals(LifecycleState.State.ACTIVE, event.previousState());
        assertEquals(LifecycleState.State.SUSPENDED, event.currentState());
    }

    @Test
    // Ensures a suspended mint can resume and emits the resumed event.
    void shouldResumeMintAndEmitEvent() {
        storeSuspendedMint();

        final ManageMintLifecycleRequest request = request(LifecycleCommand.RESUME, "resume-tag");

        final ManageMintLifecycleResponse response = interactor.handle(request);

        assertEquals(LifecycleState.State.ACTIVE, response.lifecycleState());
        final MintLifecycleEvent event = eventPublisher.events.getLast();
        assertEquals(MintLifecycleEventType.RESUMED, event.type());
        assertEquals(LifecycleState.State.SUSPENDED, event.previousState());
        assertEquals(LifecycleState.State.ACTIVE, event.currentState());
    }

    @Test
    // Ensures a mint can be retired and emits the retired event.
    void shouldRetireMintAndEmitEvent() {
        storeSuspendedMint();

        final ManageMintLifecycleRequest request = request(LifecycleCommand.RETIRE, "retire-tag");

        final ManageMintLifecycleResponse response = interactor.handle(request);

        assertEquals(LifecycleState.State.DECOMMISSIONED, response.lifecycleState());
        final MintLifecycleEvent event = eventPublisher.events.getLast();
        assertEquals(MintLifecycleEventType.RETIRED, event.type());
        assertEquals(LifecycleState.State.SUSPENDED, event.previousState());
        assertEquals(LifecycleState.State.DECOMMISSIONED, event.currentState());
    }

    @Test
    // Ensures pausing a provisioned mint without activation is rejected.
    void shouldRejectPauseWhenTransitionDisallowed() {
        storeProvisionedMint("initial");

        final ManageMintLifecycleRequest request = request(LifecycleCommand.PAUSE, "pause-tag");

        assertThrows(IllegalStateException.class, () -> interactor.handle(request));
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    // Ensures lifecycle pause fails when the mint aggregate is missing.
    void shouldRejectPauseForMissingMint() {
        final ManageMintLifecycleRequest request = request(LifecycleCommand.PAUSE, VERSION_TAG);

        assertThrows(IllegalStateException.class, () -> interactor.handle(request));
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    // Ensures lifecycle resume fails when the mint aggregate is missing.
    void shouldRejectResumeForMissingMint() {
        final ManageMintLifecycleRequest request = request(LifecycleCommand.RESUME, VERSION_TAG);

        assertThrows(IllegalStateException.class, () -> interactor.handle(request));
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    // Ensures resuming a decommissioned mint is rejected.
    void shouldRejectResumeWhenMintRetired() {
        storeRetiredMint();

        final ManageMintLifecycleRequest request = request(LifecycleCommand.RESUME, "resume-tag");

        assertThrows(IllegalStateException.class, () -> interactor.handle(request));
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    // Ensures lifecycle retire fails when the mint aggregate is missing.
    void shouldRejectRetireForMissingMint() {
        final ManageMintLifecycleRequest request = request(LifecycleCommand.RETIRE, VERSION_TAG);

        assertThrows(IllegalStateException.class, () -> interactor.handle(request));
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    // Ensures audit metadata records the supplied request and correlation identifiers.
    void shouldIncludeRequestAndCorrelationIdsInAuditMetadata() {
        final ManageMintLifecycleRequest request = request(LifecycleCommand.CREATE, VERSION_TAG);

        interactor.handle(request);

        final MintLifecycleEvent event = eventPublisher.events.getFirst();
        assertEquals(UUID.fromString(REQUEST_ID), event.auditMetadata().requestId());
        assertEquals(CORRELATION_ID, event.auditMetadata().correlationId());
    }

    @Test
    // Ensures identifiers are generated when the request omits them.
    void shouldGenerateIdentifiersWhenMissing() {
        final ManageMintLifecycleRequest request = new ManageMintLifecycleRequest(MINT_ID, OPERATOR_ID,
            LifecycleCommand.CREATE, VERSION_TAG, null, null);

        interactor.handle(request);

        final MintLifecycleEvent event = eventPublisher.events.getFirst();
        assertNotNull(event.auditMetadata().requestId());
        assertEquals(event.auditMetadata().requestId().toString(), event.auditMetadata().correlationId());
    }

    private ManageMintLifecycleRequest request(final LifecycleCommand command, final String versionTag) {
        return new ManageMintLifecycleRequest(MINT_ID, OPERATOR_ID, command, versionTag, REQUEST_ID, CORRELATION_ID);
    }

    private void storeProvisionedMint(final String versionTag) {
        final MintAggregate aggregate = createProvisioningAggregate(versionTag);
        mintRepository.save(aggregate);
        configurationSetRepository.save(aggregate.mintId(), aggregate.configurationSet());
        eventPublisher.events.clear();
        transactionManager.executionCount = 0;
    }

    private void storeActiveMint() {
        final MintAggregate provisioning = createProvisioningAggregate("initial");
        final MintAggregate provisioned = provisioning.markProvisioned(
            new AuditMetadata(OPERATOR_ID, "Vault ready", clock.instant()));
        final MintAggregate active = provisioned.activate(new AuditMetadata(OPERATOR_ID, "Activated", clock.instant()));
        mintRepository.save(active);
        configurationSetRepository.save(active.mintId(), active.configurationSet());
        eventPublisher.events.clear();
        transactionManager.executionCount = 0;
    }

    private void storeSuspendedMint() {
        final MintAggregate provisioning = createProvisioningAggregate("initial");
        final MintAggregate provisioned = provisioning.markProvisioned(
            new AuditMetadata(OPERATOR_ID, "Vault ready", clock.instant()));
        final MintAggregate active = provisioned.activate(new AuditMetadata(OPERATOR_ID, "Activated", clock.instant()));
        final MintAggregate suspended = active.suspend(new AuditMetadata(OPERATOR_ID, "Paused", clock.instant()));
        mintRepository.save(suspended);
        configurationSetRepository.save(suspended.mintId(), suspended.configurationSet());
        eventPublisher.events.clear();
        transactionManager.executionCount = 0;
    }

    private void storeRetiredMint() {
        final MintAggregate provisioning = createProvisioningAggregate("initial");
        final MintAggregate provisioned = provisioning.markProvisioned(
            new AuditMetadata(OPERATOR_ID, "Vault ready", clock.instant()));
        final MintAggregate active = provisioned.activate(new AuditMetadata(OPERATOR_ID, "Activated", clock.instant()));
        final MintAggregate suspended = active.suspend(new AuditMetadata(OPERATOR_ID, "Paused", clock.instant()));
        final MintAggregate retired = suspended.decommission(new AuditMetadata(OPERATOR_ID, "Retired", clock.instant()));
        mintRepository.save(retired);
        configurationSetRepository.save(retired.mintId(), retired.configurationSet());
        eventPublisher.events.clear();
        transactionManager.executionCount = 0;
    }

    private MintAggregate createProvisioningAggregate(final String versionTag) {
        final MintId mintId = MintId.fromString(MINT_ID);
        final UUID operatorUuid = UUID.fromString(OPERATOR_ID);
        final AuditMetadata auditMetadata = new AuditMetadata(OPERATOR_ID, "Mint created", clock.instant());
        final ConfigurationSet configuration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("version.tag", versionTag), auditMetadata);
        final OperatorAccount operatorAccount = new OperatorAccount(operatorUuid, OPERATOR_ID, Set.of("MINT_ADMIN"),
            auditMetadata);
        final NotificationPolicy notificationPolicy = new NotificationPolicy(true, false, Duration.ofMinutes(5),
            auditMetadata);
        return MintAggregate.create(mintId, configuration, operatorAccount, notificationPolicy, auditMetadata);
    }

    private static final class RecordingTransactionManager implements TransactionManager {

        private int executionCount = 0;

        @Override
        public <T> T execute(final java.util.function.Supplier<T> action) {
            executionCount++;
            return action.get();
        }
    }

    private static final class RecordingMintRepository implements MintRepository {

        private final Map<MintId, MintAggregate> storage = new HashMap<>();
        private MintAggregate lastSaved;

        @Override
        public void save(final MintAggregate aggregate) {
            storage.put(aggregate.mintId(), aggregate);
            lastSaved = aggregate;
        }

        @Override
        public Optional<MintAggregate> findById(final MintId mintId) {
            return Optional.ofNullable(storage.get(mintId));
        }

        @Override
        public List<MintAggregate> findAll() {
            return new ArrayList<>(storage.values());
        }
    }

    private static final class RecordingConfigurationSetRepository implements ConfigurationSetRepository {

        private final Map<MintId, Map<ConfigurationRevisionId, ConfigurationSet>> history = new HashMap<>();

        @Override
        public void save(final MintId mintId, final ConfigurationSet configurationSet) {
            history.computeIfAbsent(mintId, id -> new HashMap<>())
                .put(configurationSet.revisionId(), configurationSet);
        }

        @Override
        public Optional<ConfigurationSet> findByRevision(final MintId mintId,
                                                         final ConfigurationRevisionId revisionId) {
            return Optional.ofNullable(history.getOrDefault(mintId, Map.of()).get(revisionId));
        }

        @Override
        public List<ConfigurationSet> findByMintId(final MintId mintId) {
            return history.getOrDefault(mintId, Map.of()).values().stream()
                .sorted(Comparator.comparing(ConfigurationSet::revisionId))
                .toList();
        }

        int countRevisions(final MintId mintId) {
            return history.getOrDefault(mintId, Map.of()).size();
        }

        int countRevisions(final MintId mintId, final ConfigurationRevisionId revisionId) {
            return findByRevision(mintId, revisionId).isPresent() ? 1 : 0;
        }
    }

    private static final class RecordingEventPublisher implements MintLifecycleEventPublisher {

        private final List<MintLifecycleEvent> events = new ArrayList<>();

        @Override
        public void publish(final MintLifecycleEvent event) {
            events.add(event);
        }
    }
}
