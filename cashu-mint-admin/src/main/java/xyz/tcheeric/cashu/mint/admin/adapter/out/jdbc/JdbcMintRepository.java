package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
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
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.AuditTrail;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleContext;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicySnapshot;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;

/**
 * JDBC implementation of {@link MintRepository} backed by the admin schema tables.
 */
public class JdbcMintRepository implements MintRepository {

    private static final String UPSERT_MINT_SQL =
        """
            INSERT INTO mints (
                mint_id,
                lifecycle_state,
                current_configuration_revision,
                last_actor,
                last_action,
                last_timestamp,
                last_reason_codes,
                last_ticket_references,
                last_automation_automated,
                last_automation_system,
                last_automation_run_id,
                version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                lifecycle_state = EXCLUDED.lifecycle_state,
                current_configuration_revision = EXCLUDED.current_configuration_revision,
                last_actor = EXCLUDED.last_actor,
                last_action = EXCLUDED.last_action,
                last_timestamp = EXCLUDED.last_timestamp,
                last_reason_codes = EXCLUDED.last_reason_codes,
                last_ticket_references = EXCLUDED.last_ticket_references,
                last_automation_automated = EXCLUDED.last_automation_automated,
                last_automation_system = EXCLUDED.last_automation_system,
                last_automation_run_id = EXCLUDED.last_automation_run_id,
                version = EXCLUDED.version
        """;

    private static final String H2_UPSERT_MINT_SQL =
        """
            MERGE INTO mints (
                mint_id,
                lifecycle_state,
                current_configuration_revision,
                last_actor,
                last_action,
                last_timestamp,
                last_reason_codes,
                last_ticket_references,
                last_automation_automated,
                last_automation_system,
                last_automation_run_id,
                version)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPSERT_OPERATOR_SQL =
        """
            INSERT INTO operator_accounts (
                mint_id,
                operator_id,
                display_name,
                roles,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                operator_id = EXCLUDED.operator_id,
                display_name = EXCLUDED.display_name,
                roles = EXCLUDED.roles,
                audit_actor = EXCLUDED.audit_actor,
                audit_action = EXCLUDED.audit_action,
                audit_timestamp = EXCLUDED.audit_timestamp,
                audit_reason_codes = EXCLUDED.audit_reason_codes,
                audit_ticket_references = EXCLUDED.audit_ticket_references,
                audit_automation_automated = EXCLUDED.audit_automation_automated,
                audit_automation_system = EXCLUDED.audit_automation_system,
                audit_automation_run_id = EXCLUDED.audit_automation_run_id
        """;

    private static final String H2_UPSERT_OPERATOR_SQL =
        """
            MERGE INTO operator_accounts (
                mint_id,
                operator_id,
                display_name,
                roles,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPSERT_NOTIFICATION_SQL =
        """
            INSERT INTO notification_policies (
                mint_id,
                email_enabled,
                webhook_enabled,
                throttle_interval_seconds,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id) DO UPDATE SET
                email_enabled = EXCLUDED.email_enabled,
                webhook_enabled = EXCLUDED.webhook_enabled,
                throttle_interval_seconds = EXCLUDED.throttle_interval_seconds,
                audit_actor = EXCLUDED.audit_actor,
                audit_action = EXCLUDED.audit_action,
                audit_timestamp = EXCLUDED.audit_timestamp,
                audit_reason_codes = EXCLUDED.audit_reason_codes,
                audit_ticket_references = EXCLUDED.audit_ticket_references,
                audit_automation_automated = EXCLUDED.audit_automation_automated,
                audit_automation_system = EXCLUDED.audit_automation_system,
                audit_automation_run_id = EXCLUDED.audit_automation_run_id
        """;

    private static final String H2_UPSERT_NOTIFICATION_SQL =
        """
            MERGE INTO notification_policies (
                mint_id,
                email_enabled,
                webhook_enabled,
                throttle_interval_seconds,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id)
            KEY (mint_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String DELETE_AUDIT_SQL = "DELETE FROM audit_events WHERE mint_id = ?";

    private static final String INSERT_AUDIT_SQL =
        """
            INSERT INTO audit_events (mint_id, sequence, actor, action, event_timestamp,
                reason_codes,
                ticket_references,
                automation_automated,
                automation_system,
                automation_run_id,
                configuration_revision_id,
                notification_policy_email_enabled,
                notification_policy_webhook_enabled,
                notification_policy_throttle_interval_seconds,
                notification_policy_audit_actor,
                notification_policy_audit_action,
                notification_policy_audit_timestamp,
                request_id,
                correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            SELECT operator_id, display_name, roles, audit_actor, audit_action, audit_timestamp,
                   audit_reason_codes, audit_ticket_references, audit_automation_automated,
                   audit_automation_system, audit_automation_run_id
            FROM operator_accounts
            WHERE mint_id = ?
        """;

    private static final String SELECT_NOTIFICATION_SQL =
        """
            SELECT email_enabled, webhook_enabled, throttle_interval_seconds, audit_actor, audit_action, audit_timestamp,
                   audit_reason_codes, audit_ticket_references, audit_automation_automated,
                   audit_automation_system, audit_automation_run_id
            FROM notification_policies
            WHERE mint_id = ?
        """;

    private static final String SELECT_AUDIT_SQL =
        """
            SELECT sequence,
                   actor,
                   action,
                   event_timestamp,
                   reason_codes,
                   ticket_references,
                   automation_automated,
                   automation_system,
                   automation_run_id,
                   configuration_revision_id,
                   notification_policy_email_enabled,
                   notification_policy_webhook_enabled,
                   notification_policy_throttle_interval_seconds,
                   notification_policy_audit_actor,
                   notification_policy_audit_action,
                   notification_policy_audit_timestamp,
                   request_id,
                   correlation_id
            FROM audit_events
            WHERE mint_id = ?
            ORDER BY sequence
        """;

    private static final TypeReference<Set<String>> ROLE_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() { };

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
        throws SQLException, IOException {
        try (PreparedStatement statement = prepareStatement(connection, UPSERT_MINT_SQL, H2_UPSERT_MINT_SQL)) {
            final AuditMetadata audit = aggregate.auditMetadata();
            statement.setObject(1, aggregate.mintId().value());
            statement.setString(2, aggregate.lifecycleState().value().name());
            statement.setLong(3, aggregate.configurationSet().revisionId().value());
            statement.setString(4, audit.actor());
            statement.setString(5, audit.action());
            statement.setTimestamp(6, Timestamp.from(audit.timestamp()));
            statement.setString(7, writeList(audit.reasonCodes()));
            statement.setString(8, writeList(audit.ticketReferences()));
            statement.setBoolean(9, audit.automationContext().automated());
            statement.setString(10, audit.automationContext().system());
            statement.setString(11, audit.automationContext().runId());
            statement.setLong(12, aggregate.auditTrail().entries().size());
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
            statement.setString(8, writeList(audit.reasonCodes()));
            statement.setString(9, writeList(audit.ticketReferences()));
            statement.setBoolean(10, audit.automationContext().automated());
            statement.setString(11, audit.automationContext().system());
            statement.setString(12, audit.automationContext().runId());
            statement.executeUpdate();
        }
    }

    private void upsertNotification(final Connection connection,
                                    final MintId mintId,
                                    final NotificationPolicy policy)
        throws SQLException, IOException {
        try (PreparedStatement statement = prepareStatement(connection, UPSERT_NOTIFICATION_SQL, H2_UPSERT_NOTIFICATION_SQL)) {
            final AuditMetadata audit = policy.auditMetadata();
            statement.setObject(1, mintId.value());
            statement.setBoolean(2, policy.emailEnabled());
            statement.setBoolean(3, policy.webhookEnabled());
            statement.setLong(4, policy.throttleInterval().toSeconds());
            statement.setString(5, audit.actor());
            statement.setString(6, audit.action());
            statement.setTimestamp(7, Timestamp.from(audit.timestamp()));
            statement.setString(8, writeList(audit.reasonCodes()));
            statement.setString(9, writeList(audit.ticketReferences()));
            statement.setBoolean(10, audit.automationContext().automated());
            statement.setString(11, audit.automationContext().system());
            statement.setString(12, audit.automationContext().runId());
            statement.executeUpdate();
        }
    }

    private void replaceAuditTrail(final Connection connection,
                                   final MintId mintId,
                                   final AuditTrail auditTrail)
        throws SQLException, IOException {
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
                insert.setString(6, writeList(entry.reasonCodes()));
                insert.setString(7, writeList(entry.ticketReferences()));
                insert.setBoolean(8, entry.automationContext().automated());
                insert.setString(9, entry.automationContext().system());
                insert.setString(10, entry.automationContext().runId());
                final LifecycleContext context = entry.lifecycleContext();
                final ConfigurationRevisionId revisionId = context.configurationRevisionId();
                if (revisionId == null) {
                    insert.setNull(11, Types.BIGINT);
                } else {
                    insert.setLong(11, revisionId.value());
                }
                final NotificationPolicySnapshot snapshot = context.notificationPolicySnapshot();
                if (snapshot == null) {
                    insert.setNull(12, Types.BOOLEAN);
                    insert.setNull(13, Types.BOOLEAN);
                    insert.setNull(14, Types.BIGINT);
                    insert.setNull(15, Types.VARCHAR);
                    insert.setNull(16, Types.VARCHAR);
                    insert.setNull(17, Types.TIMESTAMP_WITH_TIMEZONE);
                } else {
                    insert.setBoolean(12, snapshot.emailEnabled());
                    insert.setBoolean(13, snapshot.webhookEnabled());
                    insert.setLong(14, snapshot.throttleInterval().getSeconds());
                    insert.setString(15, snapshot.auditActor());
                    insert.setString(16, snapshot.auditAction());
                    insert.setTimestamp(17, Timestamp.from(snapshot.auditTimestamp()));
                }
                insert.setObject(18, entry.requestId());
                insert.setString(19, entry.correlationId());
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
                final ConfigurationSet configuration = configurationRepository
                    .findByRevision(mintId, revisionId)
                    .orElseThrow(() -> new JdbcRepositoryException(
                        "Missing configuration revision " + revisionId.value() + " for mint " + mintId.asString()));
                final OperatorAccount operator = loadOperatorAccount(connection, mintId);
                final NotificationPolicy policy = loadNotificationPolicy(connection, mintId);
                final AuditTrail auditTrail = loadAuditTrail(connection, mintId);
                final AuditMetadata audit = auditTrail.latestMetadata();
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
                    getInstant(resultSet, "audit_timestamp"),
                    readList(resultSet, "audit_reason_codes"),
                    readList(resultSet, "audit_ticket_references"),
                    mapAutomationContext(resultSet, "audit_automation_automated", "audit_automation_system",
                        "audit_automation_run_id"));
                return new OperatorAccount(operatorId, resultSet.getString("display_name"), roles, audit);
            }
        }
    }

    private NotificationPolicy loadNotificationPolicy(final Connection connection, final MintId mintId)
        throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_NOTIFICATION_SQL)) {
            statement.setObject(1, mintId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new JdbcRepositoryException("Notification policy missing for mint " + mintId.asString());
                }
                final AuditMetadata audit = new AuditMetadata(
                    resultSet.getString("audit_actor"),
                    resultSet.getString("audit_action"),
                    getInstant(resultSet, "audit_timestamp"),
                    readList(resultSet, "audit_reason_codes"),
                    readList(resultSet, "audit_ticket_references"),
                    mapAutomationContext(resultSet, "audit_automation_automated", "audit_automation_system",
                        "audit_automation_run_id"));
                final Duration interval = Duration.ofSeconds(resultSet.getLong("throttle_interval_seconds"));
                return new NotificationPolicy(resultSet.getBoolean("email_enabled"),
                    resultSet.getBoolean("webhook_enabled"), interval, audit);
            }
        }
    }

    private AuditTrail loadAuditTrail(final Connection connection, final MintId mintId)
        throws SQLException, IOException {
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

    private AuditMetadata mapAuditEntry(final ResultSet resultSet) throws SQLException, IOException {
        final LifecycleContext context = mapLifecycleContext(resultSet);
        final UUID requestId = getNullableUuid(resultSet, "request_id");
        final String correlationId = resultSet.getString("correlation_id");
        return new AuditMetadata(resultSet.getString("actor"), resultSet.getString("action"),
            getInstant(resultSet, "event_timestamp"),
            readList(resultSet, "reason_codes"),
            readList(resultSet, "ticket_references"),
            mapAutomationContext(resultSet, "automation_automated", "automation_system", "automation_run_id"),
            context,
            requestId,
            correlationId);
    }

    private String writeList(final List<String> values) throws IOException {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return objectMapper.writeValueAsString(values);
    }

    private List<String> readList(final ResultSet resultSet, final String column) throws SQLException, IOException {
        final String raw = resultSet.getString(column);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(raw, LIST_TYPE);
    }

    private AutomationContext mapAutomationContext(final ResultSet resultSet,
                                                   final String automatedColumn,
                                                   final String systemColumn,
                                                   final String runIdColumn) throws SQLException {
        final Boolean automated = (Boolean) resultSet.getObject(automatedColumn);
        final String system = resultSet.getString(systemColumn);
        final String runId = resultSet.getString(runIdColumn);
        if (automated == null) {
            return AutomationContext.manual();
        }
        return new AutomationContext(automated, system, runId);
    }

    private LifecycleContext mapLifecycleContext(final ResultSet resultSet) throws SQLException {
        final long revisionValue = resultSet.getLong("configuration_revision_id");
        final ConfigurationRevisionId revisionId = resultSet.wasNull() ? null : ConfigurationRevisionId.of(revisionValue);

        final Boolean emailEnabled = (Boolean) resultSet.getObject("notification_policy_email_enabled");
        final Boolean webhookEnabled = (Boolean) resultSet.getObject("notification_policy_webhook_enabled");
        final Long throttleSeconds = (Long) resultSet.getObject("notification_policy_throttle_interval_seconds");
        final String auditActor = resultSet.getString("notification_policy_audit_actor");
        final String auditAction = resultSet.getString("notification_policy_audit_action");
        final Instant auditTimestamp = getInstant(resultSet, "notification_policy_audit_timestamp");

        NotificationPolicySnapshot snapshot = null;
        if (emailEnabled != null && webhookEnabled != null && throttleSeconds != null
            && auditActor != null && auditAction != null && auditTimestamp != null) {
            snapshot = new NotificationPolicySnapshot(emailEnabled, webhookEnabled, Duration.ofSeconds(throttleSeconds),
                auditActor, auditAction, auditTimestamp);
        }
        if (revisionId == null && snapshot == null) {
            return LifecycleContext.empty();
        }
        return new LifecycleContext(revisionId, snapshot);
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

    private UUID getNullableUuid(final ResultSet resultSet, final String column) throws SQLException {
        final Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        final String text = value.toString();
        if (text.isBlank()) {
            return null;
        }
        return UUID.fromString(text);
    }
}
