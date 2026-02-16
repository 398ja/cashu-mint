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
import java.util.Optional;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository;

/**
 * JDBC implementation of {@link AlertRepository}.
 */
public class JdbcAlertRepository implements AlertRepository {

    private static final String INSERT_ALERT_SQL =
        """
            INSERT INTO admin_alerts (
                alert_id,
                mint_id,
                severity,
                summary,
                labels,
                acknowledged,
                silenced,
                silence_minutes,
                updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_ALERT_SQL =
        """
            UPDATE admin_alerts
            SET mint_id = ?,
                severity = ?,
                summary = ?,
                labels = ?,
                acknowledged = ?,
                silenced = ?,
                silence_minutes = ?,
                updated_at = ?
            WHERE alert_id = ?
        """;

    private static final String SELECT_ALERT_SQL =
        """
            SELECT alert_id,
                   mint_id,
                   severity,
                   summary,
                   labels,
                   acknowledged,
                   silenced,
                   silence_minutes
            FROM admin_alerts
            WHERE alert_id = ?
        """;

    private static final String INSERT_ESCALATION_SQL =
        """
            INSERT INTO admin_alert_escalations (
                alert_id,
                policy_id,
                escalated_at)
            VALUES (?, ?, ?)
        """;

    private static final String SELECT_ESCALATIONS_SQL =
        """
            SELECT policy_id
            FROM admin_alert_escalations
            WHERE alert_id = ?
            ORDER BY escalated_at, policy_id
        """;

    private static final TypeReference<Map<String, Object>> LABEL_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcAlertRepository(final DataSource dataSource, final ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    private static final String SELECT_ALL_ALERTS_SQL =
        """
            SELECT alert_id,
                   mint_id,
                   severity,
                   summary,
                   labels,
                   acknowledged,
                   silenced,
                   silence_minutes
            FROM admin_alerts
            ORDER BY updated_at DESC
        """;

    @Override
    public List<AlertRecord> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_ALERTS_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            final List<AlertRecord> alerts = new ArrayList<>();
            while (resultSet.next()) {
                alerts.add(mapAlert(connection, resultSet));
            }
            return alerts;
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load alerts", ex);
        }
    }

    @Override
    public boolean create(final AlertRecord alert) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_ALERT_SQL)) {
            bindAlertWrite(statement, alert, 1);
            statement.setTimestamp(9, Timestamp.from(Instant.now()));
            statement.executeUpdate();
            return true;
        } catch (final SQLException ex) {
            if (isUniqueViolation(ex)) {
                return false;
            }
            throw new JdbcRepositoryException("Failed to create alert", ex);
        } catch (final IOException ex) {
            throw new JdbcRepositoryException("Failed to serialize alert labels", ex);
        }
    }

    @Override
    public Optional<AlertRecord> findById(final String alertId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALERT_SQL)) {
            statement.setString(1, alertId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapAlert(connection, resultSet));
            }
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load alert", ex);
        }
    }

    @Override
    public void update(final AlertRecord alert) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_ALERT_SQL)) {
            statement.setString(1, alert.mintId());
            statement.setString(2, alert.severity());
            statement.setString(3, alert.summary());
            statement.setString(4, objectMapper.writeValueAsString(alert.labels()));
            statement.setBoolean(5, alert.acknowledged());
            statement.setBoolean(6, alert.silenced());
            if (alert.silenceMinutes() == null) {
                statement.setNull(7, java.sql.Types.INTEGER);
            } else {
                statement.setInt(7, alert.silenceMinutes());
            }
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.setString(9, alert.alertId());
            final int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new JdbcRepositoryException("Alert not found for update: " + alert.alertId());
            }
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to update alert", ex);
        }
    }

    @Override
    public void appendEscalation(final String alertId, final String policyId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_ESCALATION_SQL)) {
            statement.setString(1, alertId);
            statement.setString(2, policyId);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to append alert escalation", ex);
        }
    }

    private void bindAlertWrite(final PreparedStatement statement,
                                final AlertRecord alert,
                                final int indexOffset) throws SQLException, IOException {
        statement.setString(indexOffset, alert.alertId());
        statement.setString(indexOffset + 1, alert.mintId());
        statement.setString(indexOffset + 2, alert.severity());
        statement.setString(indexOffset + 3, alert.summary());
        statement.setString(indexOffset + 4, objectMapper.writeValueAsString(alert.labels()));
        statement.setBoolean(indexOffset + 5, alert.acknowledged());
        statement.setBoolean(indexOffset + 6, alert.silenced());
        if (alert.silenceMinutes() == null) {
            statement.setNull(indexOffset + 7, java.sql.Types.INTEGER);
        } else {
            statement.setInt(indexOffset + 7, alert.silenceMinutes());
        }
    }

    private AlertRecord mapAlert(final Connection connection, final ResultSet resultSet)
        throws SQLException, IOException {
        final String alertId = resultSet.getString("alert_id");
        return new AlertRecord(
            alertId,
            resultSet.getString("mint_id"),
            resultSet.getString("severity"),
            resultSet.getString("summary"),
            readLabels(resultSet.getString("labels")),
            resultSet.getBoolean("acknowledged"),
            resultSet.getBoolean("silenced"),
            (Integer) resultSet.getObject("silence_minutes"),
            loadEscalations(connection, alertId));
    }

    private Map<String, Object> readLabels(final String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(raw, LABEL_TYPE);
    }

    private List<String> loadEscalations(final Connection connection, final String alertId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_ESCALATIONS_SQL)) {
            statement.setString(1, alertId);
            final List<String> escalations = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    escalations.add(resultSet.getString("policy_id"));
                }
            }
            return escalations;
        }
    }

    private boolean isUniqueViolation(final SQLException ex) {
        return "23505".equals(ex.getSQLState());
    }
}
