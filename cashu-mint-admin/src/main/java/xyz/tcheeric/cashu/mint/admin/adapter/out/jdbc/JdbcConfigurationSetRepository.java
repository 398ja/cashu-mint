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
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * JDBC implementation of {@link ConfigurationSetRepository} backed by the configuration history table.
 */
public class JdbcConfigurationSetRepository implements ConfigurationSetRepository {

    private static final String UPSERT_SQL =
        """
            INSERT INTO configuration_revisions (mint_id, revision_id, parameters, audit_actor, audit_action, audit_timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id, revision_id) DO UPDATE SET
                parameters = EXCLUDED.parameters,
                audit_actor = EXCLUDED.audit_actor,
                audit_action = EXCLUDED.audit_action,
                audit_timestamp = EXCLUDED.audit_timestamp
        """;

    private static final String H2_UPSERT_SQL =
        """
            MERGE INTO configuration_revisions (mint_id, revision_id, parameters, audit_actor, audit_action, audit_timestamp)
            KEY (mint_id, revision_id)
            VALUES (?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT_ONE_SQL =
        """
            SELECT revision_id, parameters, audit_actor, audit_action, audit_timestamp
            FROM configuration_revisions
            WHERE mint_id = ? AND revision_id = ?
        """;

    private static final String SELECT_ALL_SQL =
        """
            SELECT revision_id, parameters, audit_actor, audit_action, audit_timestamp
            FROM configuration_revisions
            WHERE mint_id = ?
            ORDER BY revision_id
        """;

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcConfigurationSetRepository(final DataSource dataSource, final ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(final MintId mintId, final ConfigurationSet configurationSet) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                save(connection, mintId, configurationSet);
                connection.commit();
            } catch (final SQLException | IOException ex) {
                connection.rollback();
                throw new JdbcRepositoryException("Failed to persist configuration revision", ex);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to persist configuration revision", ex);
        }
    }

    @Override
    public Optional<ConfigurationSet> findByRevision(final MintId mintId, final ConfigurationRevisionId revisionId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ONE_SQL)) {
            statement.setObject(1, mintId.value());
            statement.setLong(2, revisionId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(resultSet));
            }
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load configuration revision", ex);
        }
    }

    @Override
    public List<ConfigurationSet> findByMintId(final MintId mintId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SQL)) {
            statement.setObject(1, mintId.value());
            final List<ConfigurationSet> results = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(mapRow(resultSet));
                }
            }
            return List.copyOf(results);
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load configuration history", ex);
        }
    }

    void save(final Connection connection, final MintId mintId, final ConfigurationSet configurationSet)
        throws SQLException, IOException {
        try (PreparedStatement statement = prepareUpsertStatement(connection)) {
            final AuditMetadata audit = configurationSet.auditMetadata();
            statement.setObject(1, mintId.value());
            statement.setLong(2, configurationSet.revisionId().value());
            statement.setString(3, objectMapper.writeValueAsString(configurationSet.parameters()));
            statement.setString(4, audit.actor());
            statement.setString(5, audit.action());
            statement.setTimestamp(6, Timestamp.from(audit.timestamp()));
            statement.executeUpdate();
        }
    }

    private PreparedStatement prepareUpsertStatement(final Connection connection) throws SQLException {
        if (isH2(connection)) {
            return connection.prepareStatement(H2_UPSERT_SQL);
        }
        return connection.prepareStatement(UPSERT_SQL);
    }

    private boolean isH2(final Connection connection) throws SQLException {
        final String productName = connection.getMetaData().getDatabaseProductName();
        return "H2".equalsIgnoreCase(productName);
    }

    private ConfigurationSet mapRow(final ResultSet resultSet) throws SQLException, IOException {
        final ConfigurationRevisionId revisionId = ConfigurationRevisionId.of(resultSet.getLong("revision_id"));
        final Map<String, String> parameters =
            objectMapper.readValue(resultSet.getString("parameters"), MAP_TYPE);
        final AuditMetadata audit = new AuditMetadata(
            resultSet.getString("audit_actor"),
            resultSet.getString("audit_action"),
            getInstant(resultSet, "audit_timestamp"));
        return new ConfigurationSet(revisionId, parameters, audit);
    }

    private Instant getInstant(final ResultSet resultSet, final String column) throws SQLException {
        final Timestamp timestamp = resultSet.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }
}
