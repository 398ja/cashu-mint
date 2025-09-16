package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * JDBC backed implementation of the transactional outbox repository.
 */
public class JdbcOutboxRepository implements OutboxRepository {

    private static final String INSERT_SQL =
        """
            INSERT INTO admin_outbox (event_id, aggregate_id, aggregate_type, event_type, payload, attributes, occurred_at,
                                      available_at, last_attempt_at, dispatched_at, delivery_attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT_PENDING_SQL =
        """
            SELECT event_id, aggregate_id, aggregate_type, event_type, payload, attributes, occurred_at, available_at,
                   last_attempt_at, dispatched_at, delivery_attempts
            FROM admin_outbox
            WHERE dispatched_at IS NULL AND available_at <= ?
            ORDER BY available_at, event_id
            LIMIT ?
        """;

    private static final String MARK_DISPATCHED_SQL =
        """
            UPDATE admin_outbox
            SET dispatched_at = ?, last_attempt_at = ?
            WHERE event_id = ?
        """;

    private static final String RECORD_FAILURE_SQL =
        """
            UPDATE admin_outbox
            SET delivery_attempts = delivery_attempts + 1,
                last_attempt_at = ?,
                available_at = ?
            WHERE event_id = ?
        """;

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcOutboxRepository(final DataSource dataSource, final ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(final OutboxMessage message) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setObject(1, message.eventId());
            statement.setObject(2, message.aggregateId().value());
            statement.setString(3, message.aggregateType());
            statement.setString(4, message.eventType());
            statement.setString(5, message.payload());
            statement.setString(6, objectMapper.writeValueAsString(message.attributes()));
            statement.setTimestamp(7, Timestamp.from(message.occurredAt()));
            statement.setTimestamp(8, Timestamp.from(message.availableAt()));
            statement.setTimestamp(9, toTimestamp(message.lastAttemptAt()));
            statement.setTimestamp(10, toTimestamp(message.dispatchedAt()));
            statement.setInt(11, message.deliveryAttempts());
            statement.executeUpdate();
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to append outbox message", ex);
        }
    }

    @Override
    public List<OutboxMessage> findPending(final Instant availableBefore, final int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_PENDING_SQL)) {
            statement.setTimestamp(1, Timestamp.from(availableBefore));
            statement.setInt(2, limit);
            final List<OutboxMessage> messages = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(mapRow(resultSet));
                }
            }
            return List.copyOf(messages);
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load pending outbox messages", ex);
        }
    }

    @Override
    public void markDispatched(final UUID eventId, final Instant dispatchedAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_DISPATCHED_SQL)) {
            final Timestamp timestamp = Timestamp.from(dispatchedAt);
            statement.setTimestamp(1, timestamp);
            statement.setTimestamp(2, timestamp);
            statement.setObject(3, eventId);
            if (statement.executeUpdate() == 0) {
                throw new JdbcRepositoryException("Outbox message " + eventId + " not found");
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to mark outbox message dispatched", ex);
        }
    }

    @Override
    public void recordFailure(final UUID eventId, final Instant attemptAt, final Instant nextAttemptAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RECORD_FAILURE_SQL)) {
            statement.setTimestamp(1, Timestamp.from(attemptAt));
            statement.setTimestamp(2, Timestamp.from(nextAttemptAt));
            statement.setObject(3, eventId);
            if (statement.executeUpdate() == 0) {
                throw new JdbcRepositoryException("Outbox message " + eventId + " not found");
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to record outbox failure", ex);
        }
    }

    private OutboxMessage mapRow(final ResultSet resultSet) throws SQLException, IOException {
        final UUID eventId = getUuid(resultSet, "event_id");
        final MintId aggregateId = MintId.of(getUuid(resultSet, "aggregate_id"));
        final Map<String, String> attributes = objectMapper.readValue(resultSet.getString("attributes"), MAP_TYPE);
        return new OutboxMessage(eventId,
            aggregateId,
            resultSet.getString("aggregate_type"),
            resultSet.getString("event_type"),
            resultSet.getString("payload"),
            attributes,
            getInstant(resultSet, "occurred_at"),
            getInstant(resultSet, "available_at"),
            getInstant(resultSet, "last_attempt_at"),
            getInstant(resultSet, "dispatched_at"),
            resultSet.getInt("delivery_attempts"));
    }

    private Instant getInstant(final ResultSet resultSet, final String column) throws SQLException {
        final Timestamp timestamp = resultSet.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private Timestamp toTimestamp(final Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.from(instant);
    }

    private UUID getUuid(final ResultSet resultSet, final String column) throws SQLException {
        final Object value = resultSet.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(resultSet.getString(column));
    }
}
