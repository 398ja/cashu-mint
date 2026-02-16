package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * JDBC implementation of {@link OperationalControlRepository}.
 */
public class JdbcOperationalControlRepository implements OperationalControlRepository {

    private static final String INSERT_SQL =
        """
            INSERT INTO operational_controls (
                control_id,
                mint_id,
                operator_id,
                control_type,
                status,
                reason,
                duration_minutes,
                scheduled_at,
                updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL =
        """
            UPDATE operational_controls
            SET operator_id = ?,
                status = ?,
                reason = ?,
                duration_minutes = ?,
                updated_at = ?
            WHERE control_id = ?
        """;

    private static final String SELECT_ACTIVE_MAINTENANCE_SQL =
        """
            SELECT control_id,
                   mint_id,
                   operator_id,
                   control_type,
                   status,
                   reason,
                   duration_minutes,
                   scheduled_at
            FROM operational_controls
            WHERE mint_id = ?
              AND control_type = 'MAINTENANCE'
              AND status IN ('SCHEDULED', 'IN_PROGRESS')
            ORDER BY CASE status WHEN 'IN_PROGRESS' THEN 0 ELSE 1 END,
                     scheduled_at DESC
            LIMIT 1
        """;

    private static final String SELECT_BY_MINT_SQL =
        """
            SELECT control_id,
                   mint_id,
                   operator_id,
                   control_type,
                   status,
                   reason,
                   duration_minutes,
                   scheduled_at
            FROM operational_controls
            WHERE mint_id = ?
            ORDER BY scheduled_at DESC
        """;

    private final DataSource dataSource;

    public JdbcOperationalControlRepository(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void create(final OperationalControlRecord control) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindForCreate(statement, control);
            statement.executeUpdate();
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to create operational control", ex);
        }
    }

    @Override
    public void update(final OperationalControlRecord control) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            statement.setObject(1, control.operatorId());
            statement.setString(2, control.status());
            statement.setString(3, control.reason());
            if (control.durationMinutes() == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setInt(4, control.durationMinutes());
            }
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.setObject(6, UUID.fromString(control.controlId()));
            final int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new JdbcRepositoryException(
                    "Operational control not found for update: " + control.controlId());
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to update operational control", ex);
        }
    }

    @Override
    public List<OperationalControlRecord> findByMintId(final MintId mintId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_MINT_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                final List<OperationalControlRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(mapRow(resultSet));
                }
                return records;
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load operational controls for mint", ex);
        }
    }

    @Override
    public Optional<OperationalControlRecord> findActiveMaintenanceByMintId(final MintId mintId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ACTIVE_MAINTENANCE_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(resultSet));
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load active maintenance control", ex);
        }
    }

    private void bindForCreate(final PreparedStatement statement,
                               final OperationalControlRecord control) throws SQLException {
        statement.setObject(1, UUID.fromString(control.controlId()));
        statement.setObject(2, control.mintId().value());
        statement.setObject(3, control.operatorId());
        statement.setString(4, control.controlType().name());
        statement.setString(5, control.status());
        statement.setString(6, control.reason());
        if (control.durationMinutes() == null) {
            statement.setNull(7, java.sql.Types.INTEGER);
        } else {
            statement.setInt(7, control.durationMinutes());
        }
        statement.setTimestamp(8, Timestamp.from(control.scheduledAt()));
        statement.setTimestamp(9, Timestamp.from(control.scheduledAt()));
    }

    private OperationalControlRecord mapRow(final ResultSet resultSet) throws SQLException {
        final UUID controlId = getUuid(resultSet, "control_id");
        final UUID mintId = getUuid(resultSet, "mint_id");
        final UUID operatorId = getUuid(resultSet, "operator_id");
        final OperationalControlType controlType =
            OperationalControlType.valueOf(resultSet.getString("control_type"));
        final String status = resultSet.getString("status");
        final String reason = resultSet.getString("reason");
        final Integer durationMinutes = (Integer) resultSet.getObject("duration_minutes");
        final Instant scheduledAt = resultSet.getTimestamp("scheduled_at").toInstant();
        return new OperationalControlRecord(controlId.toString(), MintId.of(mintId), operatorId,
            controlType, status, scheduledAt, reason, durationMinutes);
    }

    private UUID getUuid(final ResultSet resultSet, final String column) throws SQLException {
        final Object raw = resultSet.getObject(column);
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(raw.toString());
    }
}
