package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcOutboxRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.LifecycleEventOutboxDispatcher;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.LifecycleEventOutboxHandler;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.TransactionalOutboxMintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.LifecycleCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.ManageMintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.ManageMintLifecycleResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository.MintAggregateView;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent.MintLifecycleEventType;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository.MintLifecycleHistoryEntry;
import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class ManageMintLifecycleEndToEndTest {

    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");
    private static final Duration DISPATCH_BACKOFF = Duration.ofSeconds(5);
    private static final String MINT_ID_VALUE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final UUID OPERATOR_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String REQUEST_CREATE = "00000000-0000-0000-0000-000000000001";
    private static final String REQUEST_UPDATE = "00000000-0000-0000-0000-000000000002";
    private static final String REQUEST_RESUME = "00000000-0000-0000-0000-000000000003";
    private static final String REQUEST_PAUSE = "00000000-0000-0000-0000-000000000004";
    private static final String REQUEST_RETIRE = "00000000-0000-0000-0000-000000000005";
    private static final String CORRELATION_CREATE = "create-request";
    private static final String CORRELATION_UPDATE = "update-request";
    private static final String CORRELATION_RESUME = "resume-request";
    private static final String CORRELATION_PAUSE = "pause-request";
    private static final String CORRELATION_RETIRE = "retire-request";

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private JdbcConfigurationSetRepository configurationRepository;
    private JdbcMintRepository mintRepository;
    private JdbcOutboxRepository outboxRepository;
    private JdbcMintLifecycleHistoryRepository historyRepository;
    private JdbcMintAggregateViewRepository viewRepository;
    private TransactionManager transactionManager;
    private ManageMintLifecycleInteractor interactor;
    private LifecycleEventOutboxDispatcher dispatcher;
    private Clock clock;
    private MintId mintId;

    @BeforeEach
    void setUp() {
        dataSource = createDataSource();
        objectMapper = new ObjectMapper();
        configurationRepository = new JdbcConfigurationSetRepository(dataSource, objectMapper);
        mintRepository = new JdbcMintRepository(dataSource, configurationRepository, objectMapper);
        outboxRepository = new JdbcOutboxRepository(dataSource, objectMapper);
        historyRepository = new JdbcMintLifecycleHistoryRepository(dataSource, objectMapper);
        viewRepository = new JdbcMintAggregateViewRepository(dataSource);
        transactionManager = new TransactionManager() {
            @Override
            public <T> T execute(final Supplier<T> action) {
                return action.get();
            }
        };
        clock = new SteppingClock(Instant.parse("2024-05-01T00:00:00Z"), ZoneOffset.UTC, Duration.ofSeconds(1));
        final TransactionalOutboxMintLifecycleEventPublisher eventPublisher =
            new TransactionalOutboxMintLifecycleEventPublisher(outboxRepository, objectMapper);
        interactor = new ManageMintLifecycleInteractor(mintRepository, configurationRepository, transactionManager,
            eventPublisher, clock);
        final LifecycleEventOutboxHandler handler = new LifecycleEventOutboxHandler(viewRepository, historyRepository,
            objectMapper);
        dispatcher = new LifecycleEventOutboxDispatcher(outboxRepository, handler, clock, DISPATCH_BACKOFF);
        mintId = MintId.fromString(MINT_ID_VALUE);
    }

    @Test
    // Ensures lifecycle commands persist aggregates, publish outbox entries, and project audit history consistently.
    void shouldPersistLifecycleFlowAndProjectOutboxEvents() throws Exception {
        final ManageMintLifecycleResponse created = interactor.handle(request(LifecycleCommand.CREATE, "v1",
            REQUEST_CREATE, CORRELATION_CREATE));
        assertThat(created.lifecycleState()).isEqualTo(LifecycleState.State.PROVISIONING);
        assertThat(created.versionTag()).isEqualTo("v1");

        assertSinglePendingMessage(MintLifecycleEventType.CREATED.name(),
            null, LifecycleState.State.PROVISIONING, 1L, "v1", REQUEST_CREATE, CORRELATION_CREATE);
        dispatcher.dispatchPending(10);
        assertNoPendingMessages();
        assertViewState(LifecycleState.State.PROVISIONING, 1L, "v1");
        assertHistory(MintLifecycleEventType.CREATED);

        // Simulate vault provisioning completing successfully
        final var aggregate = mintRepository.findById(mintId).orElseThrow();
        final var provisionedAudit = new xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata(
            "system", "Vault provisioned", clock.instant());
        mintRepository.save(aggregate.markProvisioned(provisionedAudit));

        final ManageMintLifecycleResponse updated = interactor.handle(request(LifecycleCommand.UPDATE_CONFIGURATION,
            "v2", REQUEST_UPDATE, CORRELATION_UPDATE));
        assertThat(updated.versionTag()).isEqualTo("v2");
        assertSinglePendingMessage(MintLifecycleEventType.CONFIGURATION_UPDATED.name(),
            LifecycleState.State.PROVISIONED, LifecycleState.State.PROVISIONED, 2L, "v2", REQUEST_UPDATE,
            CORRELATION_UPDATE);
        dispatcher.dispatchPending(10);
        assertNoPendingMessages();
        assertViewState(LifecycleState.State.PROVISIONED, 2L, "v2");
        assertHistory(MintLifecycleEventType.CREATED, MintLifecycleEventType.CONFIGURATION_UPDATED);

        final ManageMintLifecycleResponse resumed = interactor.handle(request(LifecycleCommand.RESUME, "resume-ops",
            REQUEST_RESUME, CORRELATION_RESUME));
        assertThat(resumed.lifecycleState()).isEqualTo(LifecycleState.State.ACTIVE);
        assertSinglePendingMessage(MintLifecycleEventType.RESUMED.name(), LifecycleState.State.PROVISIONED,
            LifecycleState.State.ACTIVE, 2L, "resume-ops", REQUEST_RESUME, CORRELATION_RESUME);
        dispatcher.dispatchPending(10);
        assertNoPendingMessages();
        assertViewState(LifecycleState.State.ACTIVE, 2L, "resume-ops");
        assertHistory(MintLifecycleEventType.CREATED, MintLifecycleEventType.CONFIGURATION_UPDATED,
            MintLifecycleEventType.RESUMED);

        final ManageMintLifecycleResponse paused = interactor.handle(request(LifecycleCommand.PAUSE, "pause-window",
            REQUEST_PAUSE, CORRELATION_PAUSE));
        assertThat(paused.lifecycleState()).isEqualTo(LifecycleState.State.SUSPENDED);
        assertSinglePendingMessage(MintLifecycleEventType.PAUSED.name(), LifecycleState.State.ACTIVE,
            LifecycleState.State.SUSPENDED, 2L, "pause-window", REQUEST_PAUSE, CORRELATION_PAUSE);
        dispatcher.dispatchPending(10);
        assertNoPendingMessages();
        assertViewState(LifecycleState.State.SUSPENDED, 2L, "pause-window");
        assertHistory(MintLifecycleEventType.CREATED, MintLifecycleEventType.CONFIGURATION_UPDATED,
            MintLifecycleEventType.RESUMED, MintLifecycleEventType.PAUSED);

        final ManageMintLifecycleResponse retired = interactor.handle(request(LifecycleCommand.RETIRE, "retire-final",
            REQUEST_RETIRE, CORRELATION_RETIRE));
        assertThat(retired.lifecycleState()).isEqualTo(LifecycleState.State.DECOMMISSIONED);
        assertSinglePendingMessage(MintLifecycleEventType.RETIRED.name(), LifecycleState.State.SUSPENDED,
            LifecycleState.State.DECOMMISSIONED, 2L, "retire-final", REQUEST_RETIRE, CORRELATION_RETIRE);
        dispatcher.dispatchPending(10);
        assertNoPendingMessages();
        assertViewState(LifecycleState.State.DECOMMISSIONED, 2L, "retire-final");
        assertHistory(MintLifecycleEventType.CREATED, MintLifecycleEventType.CONFIGURATION_UPDATED,
            MintLifecycleEventType.RESUMED, MintLifecycleEventType.PAUSED, MintLifecycleEventType.RETIRED);

        final var finalAggregate = mintRepository.findById(mintId).orElseThrow();
        assertThat(finalAggregate.lifecycleState().value()).isEqualTo(LifecycleState.State.DECOMMISSIONED);
        assertThat(finalAggregate.configurationSet().revisionId().value()).isEqualTo(2L);
        // 6 entries: CREATE, PROVISIONED (system), UPDATE, RESUME, PAUSE, RETIRE
        assertThat(finalAggregate.auditTrail().entries()).hasSize(6);
        assertThat(finalAggregate.auditTrail().latestLifecycleContext().configurationRevisionId().value()).isEqualTo(2L);
        assertThat(finalAggregate.auditTrail().latestMetadata().action()).isEqualTo("Mint retired");

        final List<MintLifecycleHistoryEntry> history = historyRepository.findByMintId(mintId);
        assertThat(history).hasSize(5);
        assertThat(history).extracting(entry -> entry.event().auditMetadata().requestId().toString()).containsExactly(
            REQUEST_CREATE, REQUEST_UPDATE, REQUEST_RESUME, REQUEST_PAUSE, REQUEST_RETIRE);
        assertThat(history).extracting(entry -> entry.event().auditMetadata().correlationId()).containsExactly(
            CORRELATION_CREATE, CORRELATION_UPDATE, CORRELATION_RESUME, CORRELATION_PAUSE, CORRELATION_RETIRE);
    }

    private ManageMintLifecycleRequest request(final LifecycleCommand command,
                                               final String versionTag,
                                               final String requestId,
                                               final String correlationId) {
        return new ManageMintLifecycleRequest(mintId.asString(), OPERATOR_ID.toString(), command, versionTag,
            requestId, correlationId);
    }

    private OutboxMessage assertSinglePendingMessage(final String expectedType,
                                                     final LifecycleState.State expectedPrevious,
                                                     final LifecycleState.State expectedCurrent,
                                                     final long expectedRevision,
                                                     final String expectedVersionTag,
                                                     final String expectedRequestId,
                                                     final String expectedCorrelationId) throws Exception {
        final List<OutboxMessage> pending = outboxRepository.findPending(FAR_FUTURE, 10);
        assertThat(pending).hasSize(1);
        final OutboxMessage message = pending.getFirst();
        assertThat(message.aggregateId()).isEqualTo(mintId);
        assertThat(message.aggregateType()).isEqualTo("MintAggregate");
        assertThat(message.eventType()).isEqualTo(expectedType);
        assertThat(message.attributes()).containsEntry("schema", "admin.mint-lifecycle.v1");
        assertThat(message.attributes()).containsEntry("versionTag", expectedVersionTag);
        assertThat(message.availableAt()).isEqualTo(message.occurredAt());

        final JsonNode payload = objectMapper.readTree(message.payload());
        assertThat(payload.get("eventId").asText()).isEqualTo(message.eventId().toString());
        assertThat(payload.get("type").asText()).isEqualTo(expectedType);
        assertThat(payload.get("mintId").asText()).isEqualTo(mintId.asString());
        if (expectedPrevious == null) {
            assertThat(payload.get("previousState").isNull()).isTrue();
        } else {
            assertThat(payload.get("previousState").asText()).isEqualTo(expectedPrevious.name());
        }
        assertThat(payload.get("currentState").asText()).isEqualTo(expectedCurrent.name());
        assertThat(payload.get("configurationRevision").asLong()).isEqualTo(expectedRevision);
        assertThat(payload.get("versionTag").asText()).isEqualTo(expectedVersionTag);
        final JsonNode audit = payload.get("audit");
        assertThat(audit.get("requestId").asText()).isEqualTo(expectedRequestId);
        assertThat(audit.get("correlationId").asText()).isEqualTo(expectedCorrelationId);
        return message;
    }

    private void assertViewState(final LifecycleState.State expectedState,
                                 final long expectedRevision,
                                 final String expectedVersionTag) {
        final MintAggregateView view = viewRepository.findById(mintId).orElseThrow();
        assertThat(view.lifecycleState()).isEqualTo(expectedState);
        assertThat(view.configurationRevisionId().value()).isEqualTo(expectedRevision);
        assertThat(view.versionTag()).isEqualTo(expectedVersionTag);
    }

    private void assertHistory(final MintLifecycleEventType... expectedTypes) {
        final List<MintLifecycleHistoryEntry> history = historyRepository.findByMintId(mintId);
        assertThat(history).extracting(entry -> entry.event().type()).containsExactly(expectedTypes);
    }

    private void assertNoPendingMessages() {
        assertThat(outboxRepository.findPending(FAR_FUTURE, 10)).isEmpty();
    }

    private static final class SteppingClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;
        private final Duration step;

        private SteppingClock(final Instant start, final ZoneId zone, final Duration step) {
            this.current = new AtomicReference<>(start);
            this.zone = zone;
            this.step = step;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return new SteppingClock(current.get(), zone, step);
        }

        @Override
        public Instant instant() {
            return current.getAndUpdate(previous -> previous.plus(step));
        }
    }

    private static DataSource createDataSource() {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:cashu_admin_%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
            .formatted(UUID.randomUUID()));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        initializeSchema(dataSource);
        return dataSource;
    }

    private static void initializeSchema(final DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            final String script = readSchemaScript();
            for (final String statement : script.split(";")) {
                final String trimmed = statement.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try (PreparedStatement prepared = connection.prepareStatement(trimmed)) {
                    prepared.execute();
                }
            }
        } catch (final SQLException | IOException ex) {
            throw new IllegalStateException("Failed to initialize in-memory admin schema", ex);
        }
    }

    private static String readSchemaScript() throws IOException {
        final String v1 = readMigration("/db/migration/admin/V1__create_admin_schema.sql");
        final String v2 = readMigration("/db/migration/admin/V2__link_audit_events.sql");
        final String v3 = readMigration("/db/migration/admin/V3__extend_audit_metadata.sql");
        final String v4 = readMigration("/db/migration/admin/V4__create_mint_lifecycle_history.sql");
        final String v5 = readMigration("/db/migration/admin/V5__create_mint_lifecycle_approval_states.sql");
        final String v6 = readMigration("/db/migration/admin/V6__extend_lifecycle_audit_linkage.sql");
        return (v1 + "\n" + v2 + "\n" + v3 + "\n" + v4 + "\n" + v5 + "\n" + v6)
            .replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
            .replace("    WHERE dispatched_at IS NULL", "");
    }

    private static String readMigration(final String resource) throws IOException {
        try (InputStream inputStream = ManageMintLifecycleEndToEndTest.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to locate admin schema migration script: " + resource);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
