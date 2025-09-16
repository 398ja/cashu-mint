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

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * JDBC backed projection repository for mint aggregate snapshots.
 */
public class JdbcMintAggregateViewRepository implements MintAggregateViewRepository {

    private static final String UPSERT_SQL =
        """
            INSERT INTO mint_aggregate_snapshots (
                mint_id,
                lifecycle_state,
                configuration_revision_id,
                version_tag,
                updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                lifecycle_state = EXCLUDED.lifecycle_state,
                configuration_revision_id = EXCLUDED.configuration_revision_id,
                version_tag = EXCLUDED.version_tag,
                updated_at = EXCLUDED.updated_at
        """;

    private static final String H2_UPSERT_SQL =
        """
            MERGE INTO mint_aggregate_snapshots (
                mint_id,
                lifecycle_state,
                configuration_revision_id,
                version_tag,
                updated_at)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?)
        """;

    private static final String SELECT_ONE_SQL =
        """
            SELECT lifecycle_state, configuration_revision_id, version_tag, updated_at
            FROM mint_aggregate_snapshots
            WHERE mint_id = ?
        """;

    private static final String SELECT_ALL_SQL =
        """
            SELECT mint_id, lifecycle_state, configuration_revision_id, version_tag, updated_at
            FROM mint_aggregate_snapshots
            ORDER BY mint_id
        """;

    private final DataSource dataSource;

    public JdbcMintAggregateViewRepository(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void upsert(final MintLifecycleEvent event) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepareUpsert(connection)) {
            statement.setObject(1, event.mintId().value());
            statement.setString(2, event.currentState().name());
            statement.setLong(3, event.configurationRevisionId().value());
            statement.setString(4, event.versionTag());
            statement.setTimestamp(5, Timestamp.from(event.auditMetadata().timestamp()));
            statement.executeUpdate();
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to persist mint aggregate snapshot", ex);
        }
    }

    @Override
    public Optional<MintAggregateView> findById(final MintId mintId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ONE_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(mintId.value(), resultSet));
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load mint aggregate snapshot", ex);
        }
    }

    @Override
    public List<MintAggregateView> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            final List<MintAggregateView> results = new ArrayList<>();
            while (resultSet.next()) {
                results.add(mapRow(resultSet.getObject("mint_id"), resultSet));
            }
            return List.copyOf(results);
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load mint aggregate snapshots", ex);
        }
    }

    private PreparedStatement prepareUpsert(final Connection connection) throws SQLException {
        if (isH2(connection)) {
            return connection.prepareStatement(H2_UPSERT_SQL);
        }
        return connection.prepareStatement(UPSERT_SQL);
    }

    private boolean isH2(final Connection connection) throws SQLException {
        final String productName = connection.getMetaData().getDatabaseProductName();
        return "H2".equalsIgnoreCase(productName);
    }

    private MintAggregateView mapRow(final Object rawMintId, final ResultSet resultSet) throws SQLException {
        final UUID uuid = rawMintId instanceof UUID id ? id : UUID.fromString(rawMintId.toString());
        final MintId mintId = MintId.of(uuid);
        final LifecycleState.State state = LifecycleState.State.valueOf(resultSet.getString("lifecycle_state"));
        final long revision = resultSet.getLong("configuration_revision_id");
        final String versionTag = resultSet.getString("version_tag");
        final Instant updatedAt = resultSet.getTimestamp("updated_at").toInstant();
        return new MintAggregateView(mintId, state, ConfigurationRevisionId.of(revision), versionTag, updatedAt);
    }
}
