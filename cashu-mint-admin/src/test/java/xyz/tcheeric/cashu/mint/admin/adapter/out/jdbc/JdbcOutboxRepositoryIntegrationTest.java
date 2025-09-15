package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class JdbcOutboxRepositoryIntegrationTest {

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private JdbcOutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        dataSource = TestDatabaseFactory.createDataSource();
        TestDatabaseFactory.migrate(dataSource);
        objectMapper = new ObjectMapper();
        outboxRepository = new JdbcOutboxRepository(dataSource, objectMapper);
    }

    // Ensures outbox messages can be queued, retried, and marked as dispatched.
    @Test
    void shouldHandleOutboxLifecycle() {
        final MintId aggregateId = MintId.of(UUID.randomUUID());
        final OutboxMessage message = new OutboxMessage(
            UUID.randomUUID(),
            aggregateId,
            "mint-admin",
            "MintActivated",
            "{\"event\":\"activated\"}",
            Map.of("correlationId", "123"),
            Instant.parse("2024-04-01T00:00:00Z"),
            Instant.parse("2024-04-01T00:05:00Z"),
            null,
            null,
            0);

        outboxRepository.append(message);

        List<OutboxMessage> pending = outboxRepository.findPending(Instant.parse("2024-04-01T00:06:00Z"), 10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).aggregateId()).isEqualTo(aggregateId);

        outboxRepository.recordFailure(message.eventId(), Instant.parse("2024-04-01T00:05:30Z"),
            Instant.parse("2024-04-01T00:10:00Z"));

        pending = outboxRepository.findPending(Instant.parse("2024-04-01T00:06:00Z"), 10);
        assertThat(pending).isEmpty();

        pending = outboxRepository.findPending(Instant.parse("2024-04-01T00:10:01Z"), 10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).deliveryAttempts()).isEqualTo(1);
        assertThat(pending.get(0).lastAttemptAt()).isEqualTo(Instant.parse("2024-04-01T00:05:30Z"));

        outboxRepository.markDispatched(message.eventId(), Instant.parse("2024-04-01T00:10:05Z"));

        pending = outboxRepository.findPending(Instant.parse("2024-04-01T00:20:00Z"), 10);
        assertThat(pending).isEmpty();
    }
}
