package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * JDBC implementation of {@link MintHealthRepository}.
 */
public class JdbcMintHealthRepository implements MintHealthRepository {

    private static final String UPSERT_SQL =
        """
            INSERT INTO mint_health_snapshots (
                mint_id,
                health_status,
                lifecycle_state,
                checked_at,
                updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                health_status = EXCLUDED.health_status,
                lifecycle_state = EXCLUDED.lifecycle_state,
                checked_at = EXCLUDED.checked_at,
                updated_at = EXCLUDED.updated_at
        """;

    private static final String H2_UPSERT_SQL =
        """
            MERGE INTO mint_health_snapshots (
                mint_id,
                health_status,
                lifecycle_state,
                checked_at,
                updated_at)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?)
        """;

    private static final String SELECT_SQL =
        """
            SELECT mint_id,
                   health_status,
                   lifecycle_state,
                   checked_at
            FROM mint_health_snapshots
            WHERE mint_id = ?
        """;

    private final DataSource dataSource;

    public JdbcMintHealthRepository(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void upsert(final MintHealthSnapshot snapshot) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepareUpsert(connection)) {
            statement.setObject(1, snapshot.mintId().value());
            statement.setString(2, snapshot.status().name());
            statement.setString(3, snapshot.lifecycleState());
            statement.setTimestamp(4, Timestamp.from(snapshot.checkedAt()));
            statement.setTimestamp(5, Timestamp.from(snapshot.checkedAt()));
            statement.executeUpdate();
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to persist mint health snapshot", ex);
        }
    }

    @Override
    public Optional<MintHealthSnapshot> findByMintId(final MintId mintId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                final MintId persistedMintId = MintId.of(getUuid(resultSet, "mint_id"));
                final MintHealthStatus status = MintHealthStatus.valueOf(resultSet.getString("health_status"));
                final String lifecycleState = resultSet.getString("lifecycle_state");
                final Timestamp checkedAt = resultSet.getTimestamp("checked_at");
                return Optional.of(new MintHealthSnapshot(
                    persistedMintId, status, lifecycleState, checkedAt.toInstant()));
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load mint health snapshot", ex);
        }
    }

    private PreparedStatement prepareUpsert(final Connection connection) throws SQLException {
        if (isH2(connection)) {
            return connection.prepareStatement(H2_UPSERT_SQL);
        }
        return connection.prepareStatement(UPSERT_SQL);
    }

    private boolean isH2(final Connection connection) throws SQLException {
        return "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
    }

    private UUID getUuid(final ResultSet resultSet, final String column) throws SQLException {
        final Object raw = resultSet.getObject(column);
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(raw.toString());
    }
}
