package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.AuditTrail;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;

/**
 * JDBC implementation of {@link MintRepository} backed by the admin schema tables.
 */
public class JdbcMintRepository implements MintRepository {

    private static final String UPSERT_MINT_SQL =
        """
            INSERT INTO mints (mint_id, lifecycle_state, current_configuration_revision, last_actor, last_action, last_timestamp, version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                lifecycle_state = EXCLUDED.lifecycle_state,
                current_configuration_revision = EXCLUDED.current_configuration_revision,
                last_actor = EXCLUDED.last_actor,
                last_action = EXCLUDED.last_action,
                last_timestamp = EXCLUDED.last_timestamp,
                version = EXCLUDED.version
        """;

    private static final String H2_UPSERT_MINT_SQL =
        """
            MERGE INTO mints (mint_id, lifecycle_state, current_configuration_revision, last_actor, last_action, last_timestamp, version)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPSERT_OPERATOR_SQL =
        """
            INSERT INTO operator_accounts (mint_id, operator_id, display_name, roles, audit_actor, audit_action, audit_timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                operator_id = EXCLUDED.operator_id,
                display_name = EXCLUDED.display_name,
                roles = EXCLUDED.roles,
                audit_actor = EXCLUDED.audit_actor,
                audit_action = EXCLUDED.audit_action,
                audit_timestamp = EXCLUDED.audit_timestamp
        """;

    private static final String H2_UPSERT_OPERATOR_SQL =
        """
            MERGE INTO operator_accounts (mint_id, operator_id, display_name, roles, audit_actor, audit_action, audit_timestamp)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPSERT_NOTIFICATION_SQL =
        """
            INSERT INTO notification_policies (mint_id, email_enabled, webhook_enabled, throttle_interval_seconds, audit_actor, audit_action, audit_timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                email_enabled = EXCLUDED.email_enabled,
                webhook_enabled = EXCLUDED.webhook_enabled,
                throttle_interval_seconds = EXCLUDED.throttle_interval_seconds,
                audit_actor = EXCLUDED.audit_actor,
                audit_action = EXCLUDED.audit_action,
                audit_timestamp = EXCLUDED.audit_timestamp
        """;

    private static final String H2_UPSERT_NOTIFICATION_SQL =
        """
            MERGE INTO notification_policies (mint_id, email_enabled, webhook_enabled, throttle_interval_seconds, audit_actor, audit_action, audit_timestamp)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String DELETE_AUDIT_SQL = "DELETE FROM audit_events WHERE mint_id = ?";

    private static final String INSERT_AUDIT_SQL =
        """
            INSERT INTO audit_events (mint_id, sequence, actor, action, event_timestamp)
            VALUES (?, ?, ?, ?, ?)
        """;

    private static final String SELECT_MINT_SQL =
        """
            SELECT lifecycle_state, current_configuration_revision, last_actor, last_action, last_timestamp, version
            FROM mints
            WHERE mint_id = ?
        """;

    private static final String SELECT_ALL_IDS_SQL = "SELECT mint_id FROM mints ORDER BY mint_id";

    private static final String SELECT_OPERATOR_SQL =
        """
            SELECT operator_id, display_name, roles, audit_actor, audit_action, audit_timestamp
            FROM operator_accounts
            WHERE mint_id = ?
        """;

    private static final String SELECT_NOTIFICATION_SQL =
        """
            SELECT email_enabled, webhook_enabled, throttle_interval_seconds, audit_actor, audit_action, audit_timestamp
            FROM notification_policies
            WHERE mint_id = ?
        """;

    private static final String SELECT_AUDIT_SQL =
        """
            SELECT sequence, actor, action, event_timestamp
            FROM audit_events
            WHERE mint_id = ?
            ORDER BY sequence
        """;

    private static final TypeReference<Set<String>> ROLE_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final JdbcConfigurationSetRepository configurationRepository;
    private final ObjectMapper objectMapper;

    public JdbcMintRepository(final DataSource dataSource,
                              final JdbcConfigurationSetRepository configurationRepository,
                              final ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.configurationRepository = configurationRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(final MintAggregate aggregate) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                configurationRepository.save(connection, aggregate.mintId(), aggregate.configurationSet());
                upsertMint(connection, aggregate);
                upsertOperator(connection, aggregate.mintId(), aggregate.operatorAccount());
                upsertNotification(connection, aggregate.mintId(), aggregate.notificationPolicy());
                replaceAuditTrail(connection, aggregate.mintId(), aggregate.auditTrail());
                connection.commit();
            } catch (final SQLException | IOException ex) {
                connection.rollback();
                throw new JdbcRepositoryException("Failed to persist mint aggregate", ex);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to persist mint aggregate", ex);
        }
    }

    @Override
    public Optional<MintAggregate> findById(final MintId mintId) {
        try (Connection connection = dataSource.getConnection()) {
            return loadAggregate(connection, mintId);
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load mint aggregate", ex);
        }
    }

    @Override
    public List<MintAggregate> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_IDS_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            final List<MintAggregate> aggregates = new ArrayList<>();
            while (resultSet.next()) {
                final UUID rawId = getUuid(resultSet, "mint_id");
                final MintId mintId = MintId.of(rawId);
                loadAggregate(connection, mintId).ifPresent(aggregates::add);
            }
            return List.copyOf(aggregates);
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load mint aggregates", ex);
        }
    }

    private void upsertMint(final Connection connection, final MintAggregate aggregate)
        throws SQLException {
        try (PreparedStatement statement = prepareStatement(connection, UPSERT_MINT_SQL, H2_UPSERT_MINT_SQL)) {
            final AuditMetadata audit = aggregate.auditMetadata();
            statement.setObject(1, aggregate.mintId().value());
            statement.setString(2, aggregate.lifecycleState().value().name());
            statement.setLong(3, aggregate.configurationSet().revisionId().value());
            statement.setString(4, audit.actor());
            statement.setString(5, audit.action());
            statement.setTimestamp(6, Timestamp.from(audit.timestamp()));
            statement.setLong(7, aggregate.auditTrail().entries().size());
            statement.executeUpdate();
        }
    }

    private void upsertOperator(final Connection connection, final MintId mintId, final OperatorAccount operator)
        throws SQLException, IOException {
        try (PreparedStatement statement = prepareStatement(connection, UPSERT_OPERATOR_SQL, H2_UPSERT_OPERATOR_SQL)) {
            final AuditMetadata audit = operator.auditMetadata();
            statement.setObject(1, mintId.value());
            statement.setObject(2, operator.operatorId());
            statement.setString(3, operator.displayName());
            statement.setString(4, objectMapper.writeValueAsString(operator.roles()));
            statement.setString(5, audit.actor());
            statement.setString(6, audit.action());
            statement.setTimestamp(7, Timestamp.from(audit.timestamp()));
            statement.executeUpdate();
        }
    }

    private void upsertNotification(final Connection connection,
                                    final MintId mintId,
                                    final NotificationPolicy policy)
        throws SQLException {
        try (PreparedStatement statement = prepareStatement(connection, UPSERT_NOTIFICATION_SQL, H2_UPSERT_NOTIFICATION_SQL)) {
            final AuditMetadata audit = policy.auditMetadata();
            statement.setObject(1, mintId.value());
            statement.setBoolean(2, policy.emailEnabled());
            statement.setBoolean(3, policy.webhookEnabled());
            statement.setLong(4, policy.throttleInterval().toSeconds());
            statement.setString(5, audit.actor());
            statement.setString(6, audit.action());
            statement.setTimestamp(7, Timestamp.from(audit.timestamp()));
            statement.executeUpdate();
        }
    }

    private void replaceAuditTrail(final Connection connection,
                                   final MintId mintId,
                                   final AuditTrail auditTrail)
        throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(DELETE_AUDIT_SQL)) {
            delete.setObject(1, mintId.value());
            delete.executeUpdate();
        }
        final List<AuditMetadata> entries = auditTrail.entries();
        for (int index = 0; index < entries.size(); index++) {
            final AuditMetadata entry = entries.get(index);
            try (PreparedStatement insert = connection.prepareStatement(INSERT_AUDIT_SQL)) {
                insert.setObject(1, mintId.value());
                insert.setLong(2, index + 1);
                insert.setString(3, entry.actor());
                insert.setString(4, entry.action());
                insert.setTimestamp(5, Timestamp.from(entry.timestamp()));
                insert.executeUpdate();
            }
        }
    }

    private Optional<MintAggregate> loadAggregate(final Connection connection, final MintId mintId)
        throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_MINT_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                final LifecycleState lifecycleState = LifecycleState.of(
                    LifecycleState.State.valueOf(resultSet.getString("lifecycle_state")));
                final ConfigurationRevisionId revisionId =
                    ConfigurationRevisionId.of(resultSet.getLong("current_configuration_revision"));
                final AuditMetadata audit = new AuditMetadata(
                    resultSet.getString("last_actor"),
                    resultSet.getString("last_action"),
                    getInstant(resultSet, "last_timestamp"));
                final ConfigurationSet configuration = configurationRepository
                    .findByRevision(mintId, revisionId)
                    .orElseThrow(() -> new JdbcRepositoryException(
                        "Missing configuration revision " + revisionId.value() + " for mint " + mintId.asString()));
                final OperatorAccount operator = loadOperatorAccount(connection, mintId);
                final NotificationPolicy policy = loadNotificationPolicy(connection, mintId);
                final AuditTrail auditTrail = loadAuditTrail(connection, mintId);
                return Optional.of(MintAggregate.reconstitute(mintId, lifecycleState, configuration, operator, policy,
                    auditTrail, audit));
            }
        }
    }

    private OperatorAccount loadOperatorAccount(final Connection connection, final MintId mintId)
        throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_OPERATOR_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new JdbcRepositoryException("Operator account missing for mint " + mintId.asString());
                }
                final UUID operatorId = getUuid(resultSet, "operator_id");
                final Set<String> roles = new LinkedHashSet<>(
                    objectMapper.readValue(resultSet.getString("roles"), ROLE_TYPE));
                final AuditMetadata audit = new AuditMetadata(
                    resultSet.getString("audit_actor"),
                    resultSet.getString("audit_action"),
                    getInstant(resultSet, "audit_timestamp"));
                return new OperatorAccount(operatorId, resultSet.getString("display_name"), roles, audit);
            }
        }
    }

    private NotificationPolicy loadNotificationPolicy(final Connection connection, final MintId mintId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_NOTIFICATION_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new JdbcRepositoryException("Notification policy missing for mint " + mintId.asString());
                }
                final AuditMetadata audit = new AuditMetadata(
                    resultSet.getString("audit_actor"),
                    resultSet.getString("audit_action"),
                    getInstant(resultSet, "audit_timestamp"));
                final Duration interval = Duration.ofSeconds(resultSet.getLong("throttle_interval_seconds"));
                return new NotificationPolicy(resultSet.getBoolean("email_enabled"),
                    resultSet.getBoolean("webhook_enabled"), interval, audit);
            }
        }
    }

    private AuditTrail loadAuditTrail(final Connection connection, final MintId mintId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_AUDIT_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new JdbcRepositoryException("Audit trail missing for mint " + mintId.asString());
                }
                AuditTrail trail = AuditTrail.create(mapAuditEntry(resultSet));
                while (resultSet.next()) {
                    trail = trail.append(mapAuditEntry(resultSet));
                }
                return trail;
            }
        }
    }

    private AuditMetadata mapAuditEntry(final ResultSet resultSet) throws SQLException {
        return new AuditMetadata(resultSet.getString("actor"), resultSet.getString("action"),
            getInstant(resultSet, "event_timestamp"));
    }

    private Instant getInstant(final ResultSet resultSet, final String column) throws SQLException {
        final Timestamp timestamp = resultSet.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private PreparedStatement prepareStatement(final Connection connection,
                                               final String postgresSql,
                                               final String h2Sql) throws SQLException {
        if (isH2(connection)) {
            return connection.prepareStatement(h2Sql);
        }
        return connection.prepareStatement(postgresSql);
    }

    private boolean isH2(final Connection connection) throws SQLException {
        final String productName = connection.getMetaData().getDatabaseProductName();
        return "H2".equalsIgnoreCase(productName);
    }

    private UUID getUuid(final ResultSet resultSet, final String column) throws SQLException {
        final Object value = resultSet.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(resultSet.getString(column));
    }
}
