package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * JDBC implementation persisting lifecycle event history for mint aggregates.
 */
public class JdbcMintLifecycleHistoryRepository implements MintLifecycleHistoryRepository {

    private static final String INSERT_SQL =
        """
            INSERT INTO mint_lifecycle_history (
                event_id,
                mint_id,
                event_type,
                previous_state,
                current_state,
                configuration_revision_id,
                version_tag,
                audit_actor,
                audit_action,
                audit_timestamp,
                audit_reason_codes,
                audit_ticket_references,
                audit_automation_automated,
                audit_automation_system,
                audit_automation_run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT_BY_MINT_SQL =
        """
            SELECT event_id, event_type, previous_state, current_state, configuration_revision_id,
                   version_tag, audit_actor, audit_action, audit_timestamp, audit_reason_codes,
                   audit_ticket_references, audit_automation_automated, audit_automation_system,
                   audit_automation_run_id
            FROM mint_lifecycle_history
            WHERE mint_id = ?
            ORDER BY audit_timestamp, event_id
        """;

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcMintLifecycleHistoryRepository(final DataSource dataSource, final ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(final UUID eventId, final MintLifecycleEvent event) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            final AuditMetadata audit = event.auditMetadata();
            statement.setObject(1, eventId);
            statement.setObject(2, event.mintId().value());
            statement.setString(3, event.type().name());
            statement.setString(4, toState(event.previousState()));
            statement.setString(5, event.currentState().name());
            statement.setLong(6, event.configurationRevisionId().value());
            statement.setString(7, event.versionTag());
            statement.setString(8, audit.actor());
            statement.setString(9, audit.action());
            statement.setTimestamp(10, Timestamp.from(audit.timestamp()));
            statement.setString(11, writeList(audit.reasonCodes()));
            statement.setString(12, writeList(audit.ticketReferences()));
            statement.setBoolean(13, audit.automationContext().automated());
            statement.setString(14, audit.automationContext().system());
            statement.setString(15, audit.automationContext().runId());
            statement.executeUpdate();
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to append lifecycle history entry", ex);
        }
    }

    @Override
    public List<MintLifecycleHistoryEntry> findByMintId(final MintId mintId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_MINT_SQL)) {
            statement.setObject(1, mintId.value());
            final List<MintLifecycleHistoryEntry> entries = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(mapRow(mintId, resultSet));
                }
            }
            return List.copyOf(entries);
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load lifecycle history", ex);
        }
    }

    private MintLifecycleHistoryEntry mapRow(final MintId mintId, final ResultSet resultSet)
        throws SQLException, IOException {
        final UUID eventId = getUuid(resultSet, "event_id");
        final MintLifecycleEvent.MintLifecycleEventType type =
            MintLifecycleEvent.MintLifecycleEventType.valueOf(resultSet.getString("event_type"));
        final LifecycleState.State previous = fromNullableState(resultSet.getString("previous_state"));
        final LifecycleState.State current = LifecycleState.State.valueOf(resultSet.getString("current_state"));
        final ConfigurationRevisionId revision = ConfigurationRevisionId.of(resultSet.getLong("configuration_revision_id"));
        final String versionTag = resultSet.getString("version_tag");
        final AuditMetadata audit = new AuditMetadata(
            resultSet.getString("audit_actor"),
            resultSet.getString("audit_action"),
            resultSet.getTimestamp("audit_timestamp").toInstant(),
            readList(resultSet, "audit_reason_codes"),
            readList(resultSet, "audit_ticket_references"),
            new AutomationContext(
                resultSet.getBoolean("audit_automation_automated"),
                resultSet.getString("audit_automation_system"),
                resultSet.getString("audit_automation_run_id"))
        );
        final MintLifecycleEvent event = new MintLifecycleEvent(type, mintId, previous, current, revision, versionTag, audit);
        return new MintLifecycleHistoryEntry(eventId, event);
    }

    private String writeList(final List<String> values) throws IOException {
        if (values == null || values.isEmpty()) {
            return null;
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

    private String toState(final LifecycleState.State state) {
        if (state == null) {
            return null;
        }
        return state.name();
    }

    private LifecycleState.State fromNullableState(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LifecycleState.State.valueOf(raw);
    }

    private UUID getUuid(final ResultSet resultSet, final String column) throws SQLException {
        final Object raw = resultSet.getObject(column);
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(raw.toString());
    }
}
