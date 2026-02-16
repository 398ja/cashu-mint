package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class VaultProvisioningOutboxHandlerTest {

    private static final String MINT_ID_STR = "123e4567-e89b-12d3-a456-426614174000";
    private static final MintId MINT_ID = MintId.fromString(MINT_ID_STR);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private RecordingVaultPort vaultPort;
    private RecordingMintRepository mintRepository;
    private RecordingConfigSetRepository configSetRepository;
    private RecordingEventPublisher eventPublisher;
    private VaultProvisioningOutboxHandler handler;

    @BeforeEach
    void setUp() {
        vaultPort = new RecordingVaultPort();
        mintRepository = new RecordingMintRepository();
        configSetRepository = new RecordingConfigSetRepository();
        eventPublisher = new RecordingEventPublisher();
        handler = new VaultProvisioningOutboxHandler(vaultPort, mintRepository, configSetRepository,
            eventPublisher, new ObjectMapper(), CLOCK, 3);
    }

    @Test
    // Ensures successful vault provisioning transitions mint to PROVISIONED.
    void shouldProvisionVaultAndTransitionToProvisioned() {
        storeProvisioningMint();
        final OutboxMessage message = createdMessage(0);

        handler.handle(message);

        assertThat(vaultPort.provisionedMints).containsExactly(UUID.fromString(MINT_ID_STR));
        final MintAggregate saved = mintRepository.lastSaved;
        assertThat(saved.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISIONED);
        assertThat(eventPublisher.events).hasSize(1);
        assertThat(eventPublisher.events.getFirst().type())
            .isEqualTo(MintLifecycleEvent.MintLifecycleEventType.VAULT_PROVISIONED);
    }

    @Test
    // Ensures failed provisioning throws for retry when max retries not reached.
    void shouldThrowForRetryWhenProvisioningFails() {
        storeProvisioningMint();
        vaultPort.shouldFail = true;
        final OutboxMessage message = createdMessage(0);

        assertThatThrownBy(() -> handler.handle(message))
            .isInstanceOf(OutboxMessageHandlingException.class);

        assertThat(mintRepository.lastSaved).isNull();
        assertThat(eventPublisher.events).isEmpty();
    }

    @Test
    // Ensures permanent failure triggers compensation and PROVISION_FAILED state.
    void shouldCompensateAndFailAfterMaxRetries() {
        storeProvisioningMint();
        vaultPort.shouldFail = true;
        final OutboxMessage message = createdMessage(2);

        handler.handle(message);

        assertThat(vaultPort.compensatedMints).containsExactly(UUID.fromString(MINT_ID_STR));
        final MintAggregate saved = mintRepository.lastSaved;
        assertThat(saved.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISION_FAILED);
        assertThat(eventPublisher.events).hasSize(1);
        assertThat(eventPublisher.events.getFirst().type())
            .isEqualTo(MintLifecycleEvent.MintLifecycleEventType.VAULT_PROVISION_FAILED);
    }

    @Test
    // Ensures RETIRED events trigger vault archive.
    void shouldArchiveVaultOnRetired() {
        final OutboxMessage message = retiredMessage();

        handler.handle(message);

        assertThat(vaultPort.archivedMints).containsExactly(UUID.fromString(MINT_ID_STR));
    }

    @Test
    // Ensures non-CREATED/non-RETIRED events are ignored.
    void shouldIgnoreIrrelevantEventTypes() {
        final OutboxMessage message = messageWithType("VAULT_PROVISIONED", 0);

        handler.handle(message);

        assertThat(vaultPort.provisionedMints).isEmpty();
        assertThat(vaultPort.archivedMints).isEmpty();
        assertThat(eventPublisher.events).isEmpty();
    }

    @Test
    // Ensures unit and denominations are read from configuration.
    void shouldReadUnitFromConfiguration() {
        storeProvisioningMint();
        final AuditMetadata audit = new AuditMetadata("op", "config", CLOCK.instant());
        final ConfigurationSet config = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("version.tag", "v1", "cashu.unit", "usd", "cashu.denominations", "1,5,10"),
            audit);
        configSetRepository.save(MINT_ID, config);
        final OutboxMessage message = createdMessage(0);

        handler.handle(message);

        assertThat(vaultPort.lastUnit).isEqualTo("usd");
        assertThat(vaultPort.lastDenominations).containsExactly(1, 5, 10);
    }

    private void storeProvisioningMint() {
        final AuditMetadata audit = new AuditMetadata("operator", "Mint created", CLOCK.instant());
        final ConfigurationSet config = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("version.tag", "v1"), audit);
        final OperatorAccount operator = new OperatorAccount(UUID.randomUUID(), "Operator",
            Set.of("MINT_ADMIN"), audit);
        final NotificationPolicy policy = new NotificationPolicy(true, false, Duration.ofMinutes(5), audit);
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, config, operator, policy, audit);
        mintRepository.save(aggregate);
        mintRepository.lastSaved = null;
    }

    private OutboxMessage createdMessage(final int attempts) {
        return messageWithType("CREATED", attempts);
    }

    private OutboxMessage retiredMessage() {
        return messageWithType("RETIRED", 0);
    }

    private OutboxMessage messageWithType(final String type, final int attempts) {
        final String payload = "{\"mintId\":\"" + MINT_ID_STR + "\",\"type\":\"" + type
            + "\",\"versionTag\":\"v1\",\"currentState\":\"PROVISIONING\",\"configurationRevision\":1}";
        return new OutboxMessage(UUID.randomUUID(), MINT_ID, "MintAggregate", type, payload,
            Map.of(), Instant.now(), Instant.now(), null, null, attempts);
    }

    private static final class RecordingVaultPort implements VaultProvisioningPort {
        final List<UUID> provisionedMints = new ArrayList<>();
        final List<UUID> archivedMints = new ArrayList<>();
        final List<UUID> compensatedMints = new ArrayList<>();
        boolean shouldFail = false;
        String lastUnit;
        List<Integer> lastDenominations;

        @Override
        public void provision(final UUID mintId, final String unit, final List<Integer> denominations) {
            if (shouldFail) throw new RuntimeException("vault unavailable");
            provisionedMints.add(mintId);
            lastUnit = unit;
            lastDenominations = denominations;
        }

        @Override
        public boolean isProvisioned(final UUID mintId) {
            return provisionedMints.contains(mintId);
        }

        @Override
        public void archive(final UUID mintId) {
            archivedMints.add(mintId);
        }

        @Override
        public void compensate(final UUID mintId) {
            compensatedMints.add(mintId);
        }
    }

    private static final class RecordingMintRepository implements MintRepository {
        private final Map<MintId, MintAggregate> storage = new HashMap<>();
        MintAggregate lastSaved;

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

    private static final class RecordingConfigSetRepository implements ConfigurationSetRepository {
        private final Map<MintId, Map<ConfigurationRevisionId, ConfigurationSet>> data = new HashMap<>();

        @Override
        public void save(final MintId mintId, final ConfigurationSet configurationSet) {
            data.computeIfAbsent(mintId, k -> new HashMap<>())
                .put(configurationSet.revisionId(), configurationSet);
        }

        @Override
        public Optional<ConfigurationSet> findByRevision(final MintId mintId,
                                                          final ConfigurationRevisionId revisionId) {
            return Optional.ofNullable(data.getOrDefault(mintId, Map.of()).get(revisionId));
        }

        @Override
        public List<ConfigurationSet> findByMintId(final MintId mintId) {
            return data.getOrDefault(mintId, Map.of()).values().stream()
                .sorted(Comparator.comparing(ConfigurationSet::revisionId))
                .toList();
        }
    }

    private static final class RecordingEventPublisher implements MintLifecycleEventPublisher {
        final List<MintLifecycleEvent> events = new ArrayList<>();

        @Override
        public void publish(final MintLifecycleEvent event) {
            events.add(event);
        }
    }
}
