package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionHistory;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSecret;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationValue;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * JDBC implementation of {@link ConfigurationSetRepository} backed by the configuration history table.
 */
public class JdbcConfigurationSetRepository implements ConfigurationSetRepository {

    private static final String UPSERT_SQL =
        """
            INSERT INTO configuration_revisions (
                mint_id,
                revision_id,
                parameters,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mint_id, revision_id) DO UPDATE SET
                parameters = EXCLUDED.parameters,
                audit_actor = EXCLUDED.audit_actor,
                audit_action = EXCLUDED.audit_action,
                audit_timestamp = EXCLUDED.audit_timestamp,
                audit_reason_codes = EXCLUDED.audit_reason_codes,
                audit_ticket_references = EXCLUDED.audit_ticket_references,
                audit_automation_automated = EXCLUDED.audit_automation_automated,
                audit_automation_system = EXCLUDED.audit_automation_system,
                audit_automation_run_id = EXCLUDED.audit_automation_run_id
        """;

    private static final String H2_UPSERT_SQL =
        """
            MERGE INTO configuration_revisions (
                mint_id,
                revision_id,
                parameters,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id)
            KEY (mint_id, revision_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT_ONE_SQL =
        """
            SELECT
                revision_id,
                parameters,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id
            FROM configuration_revisions
            WHERE mint_id = ? AND revision_id = ?
        """;

    private static final String SELECT_ALL_SQL =
        """
            SELECT
                revision_id,
                parameters,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id
            FROM configuration_revisions
            WHERE mint_id = ?
            ORDER BY revision_id
        """;

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() { };

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
            statement.setString(3, writeParameters(configurationSet.parameters()));
            statement.setString(4, audit.actor());
            statement.setString(5, audit.action());
            statement.setTimestamp(6, Timestamp.from(audit.timestamp()));
            statement.setString(7, writeList(audit.reasonCodes()));
            statement.setString(8, writeList(audit.ticketReferences()));
            statement.setBoolean(9, audit.automationContext().automated());
            statement.setString(10, audit.automationContext().system());
            statement.setString(11, audit.automationContext().runId());
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
        final Map<String, ConfigurationValue> parameters = readParameters(resultSet.getString("parameters"));
        final AuditMetadata audit = new AuditMetadata(
            resultSet.getString("audit_actor"),
            resultSet.getString("audit_action"),
            getInstant(resultSet, "audit_timestamp"),
            readList(resultSet, "audit_reason_codes"),
            readList(resultSet, "audit_ticket_references"),
            mapAutomationContext(resultSet));
        final ConfigurationRevisionHistory history =
            ConfigurationRevisionHistory.initial(ConfigurationRevisionState.DRAFT, audit);
        return new ConfigurationSet(revisionId, parameters, audit, ConfigurationRevisionState.DRAFT, history, null, null);
    }

    private Map<String, ConfigurationValue> readParameters(final String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        final JsonNode root = objectMapper.readTree(raw);
        final Map<String, ConfigurationValue> parameters = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            final String key = entry.getKey();
            final JsonNode valueNode = entry.getValue();
            final ConfigurationValue value;
            if (valueNode == null || valueNode.isNull()) {
                throw new IllegalStateException("configuration parameter " + key + " is missing a value");
            } else if (valueNode.isObject()) {
                final JsonNode secretNode = valueNode.get("secret");
                if (secretNode != null && !secretNode.isNull() && !(secretNode.isBoolean() && !secretNode.booleanValue())) {
                    try {
                        final ConfigurationSecret secret = objectMapper.treeToValue(secretNode, ConfigurationSecret.class);
                        value = ConfigurationValue.ofSecret(secret);
                    } catch (final IOException ex) {
                        throw new IllegalStateException("Failed to deserialize secret for parameter " + key, ex);
                    }
                } else {
                    final JsonNode plainNode = valueNode.get("value");
                    final String plainValue = plainNode == null || plainNode.isNull()
                        ? ""
                        : plainNode.asText();
                    value = ConfigurationValue.ofPlainText(plainValue);
                }
            } else if (valueNode.isTextual()) {
                value = ConfigurationValue.ofPlainText(valueNode.asText());
            } else if (valueNode.isNumber() || valueNode.isBoolean()) {
                value = ConfigurationValue.ofPlainText(valueNode.asText());
            } else {
                throw new IllegalStateException("Unsupported configuration parameter encoding for key " + key);
            }
            parameters.put(key, value);
        });
        return Map.copyOf(parameters);
    }

    private String writeParameters(final Map<String, ConfigurationValue> parameters) throws IOException {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        final Map<String, Object> serialized = new LinkedHashMap<>();
        for (final Map.Entry<String, ConfigurationValue> entry : parameters.entrySet()) {
            final ConfigurationValue value = entry.getValue();
            if (value.isSecret()) {
                serialized.put(entry.getKey(), Map.of("secret", value.secret()));
            } else {
                serialized.put(entry.getKey(), Map.of("value", value.resolvedValue()));
            }
        }
        return objectMapper.writeValueAsString(serialized);
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

    private AutomationContext mapAutomationContext(final ResultSet resultSet) throws SQLException {
        final Boolean automated = (Boolean) resultSet.getObject("audit_automation_automated");
        final String system = resultSet.getString("audit_automation_system");
        final String runId = resultSet.getString("audit_automation_run_id");
        if (automated == null) {
            return AutomationContext.manual();
        }
        return new AutomationContext(automated, system, runId);
    }

    private Instant getInstant(final ResultSet resultSet, final String column) throws SQLException {
        final Timestamp timestamp = resultSet.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }
}
