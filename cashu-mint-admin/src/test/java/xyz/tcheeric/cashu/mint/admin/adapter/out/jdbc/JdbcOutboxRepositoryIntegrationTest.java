package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private JdbcOutboxRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcOutboxRepository(dataSource, new ObjectMapper());
    }

    // Validates that pending messages are returned in ascending order of their availability window.
    @Test
    void shouldAppendAndLoadPendingMessagesInOrder() {
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final OutboxMessage first = new OutboxMessage(UUID.randomUUID(), MintId.of(UUID.randomUUID()),
            "MintAggregate", "MintCreated", "{\"event\":\"created\"}", Map.of("priority", "high"), baseTime,
            baseTime, null, null, 0);
        final OutboxMessage second = new OutboxMessage(UUID.randomUUID(), MintId.of(UUID.randomUUID()),
            "MintAggregate", "MintUpdated", "{\"event\":\"updated\"}", Map.of(), baseTime,
            baseTime.plusSeconds(5), null, null, 0);
        final OutboxMessage later = new OutboxMessage(UUID.randomUUID(), MintId.of(UUID.randomUUID()),
            "MintAggregate", "MintArchived", "{\"event\":\"archived\"}", Map.of(), baseTime,
            baseTime.plusSeconds(30), null, null, 0);

        repository.append(first);
        repository.append(second);
        repository.append(later);

        final List<OutboxMessage> pending = repository.findPending(baseTime.plusSeconds(10), 10);

        assertThat(pending).containsExactly(first, second);
    }

    // Checks that marking a message as dispatched updates timestamps and excludes it from pending results.
    @Test
    void shouldMarkMessageAsDispatched() throws SQLException {
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final OutboxMessage message = new OutboxMessage(UUID.randomUUID(), MintId.of(UUID.randomUUID()),
            "MintAggregate", "MintCreated", "{\"event\":\"created\"}", Map.of(), baseTime, baseTime, null, null,
            0);
        repository.append(message);

        final Instant dispatchTime = baseTime.plusSeconds(5);
        repository.markDispatched(message.eventId(), dispatchTime);

        final List<OutboxMessage> pending = repository.findPending(dispatchTime.plusSeconds(1), 10);
        assertThat(pending).isEmpty();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT dispatched_at, last_attempt_at
                 FROM admin_outbox
                 WHERE event_id = ?
                 """)) {
            statement.setObject(1, message.eventId());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getTimestamp("dispatched_at").toInstant()).isEqualTo(dispatchTime);
                assertThat(resultSet.getTimestamp("last_attempt_at").toInstant()).isEqualTo(dispatchTime);
            }
        }
    }

    // Validates that recording a failure increments attempts and reschedules the message correctly.
    @Test
    void shouldRecordFailureForMessage() throws SQLException {
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final OutboxMessage message = new OutboxMessage(UUID.randomUUID(), MintId.of(UUID.randomUUID()),
            "MintAggregate", "MintCreated", "{\"event\":\"created\"}", Map.of(), baseTime, baseTime, null, null,
            0);
        repository.append(message);

        final Instant attemptAt = baseTime.plusSeconds(5);
        final Instant retryAt = attemptAt.plusSeconds(60);

        repository.recordFailure(message.eventId(), attemptAt, retryAt);

        final List<OutboxMessage> beforeRetry = repository.findPending(retryAt.minusSeconds(1), 10);
        assertThat(beforeRetry).isEmpty();

        final List<OutboxMessage> pending = repository.findPending(retryAt.plusSeconds(1), 10);
        final OutboxMessage expected = message.scheduleRetry(attemptAt, retryAt);
        assertThat(pending).containsExactly(expected);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT delivery_attempts, last_attempt_at, available_at
                 FROM admin_outbox
                 WHERE event_id = ?
                 """)) {
            statement.setObject(1, message.eventId());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt("delivery_attempts")).isEqualTo(1);
                assertThat(resultSet.getTimestamp("last_attempt_at").toInstant()).isEqualTo(attemptAt);
                assertThat(resultSet.getTimestamp("available_at").toInstant()).isEqualTo(retryAt);
            }
        }
    }
}
