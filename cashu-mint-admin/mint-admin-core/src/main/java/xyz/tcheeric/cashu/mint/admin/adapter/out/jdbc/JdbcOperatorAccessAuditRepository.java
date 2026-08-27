package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessAuditRepository;

/**
 * JDBC implementation of {@link OperatorAccessAuditRepository}.
 */
public class JdbcOperatorAccessAuditRepository implements OperatorAccessAuditRepository {

    private static final String INSERT_SQL =
        """
            INSERT INTO operator_access_audit (actor, action, target_account_id, occurred_at)
            VALUES (?, ?, ?, ?)
        """;

    private static final String SELECT_ALL_SQL =
        """
            SELECT actor, action, target_account_id, occurred_at
            FROM operator_access_audit
            ORDER BY occurred_at DESC, entry_id DESC
        """;

    private final DataSource dataSource;

    public JdbcOperatorAccessAuditRepository(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void record(final OperatorAccessAuditEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, entry.actor());
            statement.setString(2, entry.action());
            statement.setString(3, entry.targetAccountId());
            statement.setTimestamp(4, Timestamp.from(entry.occurredAt()));
            statement.executeUpdate();
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to record operator access audit entry", ex);
        }
    }

    @Override
    public List<OperatorAccessAuditEntry> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            final List<OperatorAccessAuditEntry> entries = new ArrayList<>();
            while (resultSet.next()) {
                entries.add(new OperatorAccessAuditEntry(
                    resultSet.getString("actor"),
                    resultSet.getString("action"),
                    resultSet.getString("target_account_id"),
                    resultSet.getTimestamp("occurred_at").toInstant()));
            }
            return entries;
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load operator access audit trail", ex);
        }
    }
}
