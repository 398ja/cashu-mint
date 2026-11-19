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

import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * JDBC implementation of {@link RedemptionCutoffRequestRepository} (cashu-mint#551).
 */
public class JdbcRedemptionCutoffRequestRepository implements RedemptionCutoffRequestRepository {

    private static final String COLUMNS =
        "request_id, mint_id, keyset_id, redeem_until, reason, requested_by, requested_at, status, "
            + "decided_by, decided_at, outcome";

    private static final String INSERT_SQL =
        "INSERT INTO keyset_redemption_cutoff_requests (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    // Compare-and-set on status, so two Operators deciding at once cannot both win.
    private static final String TRANSITION_SQL =
        """
            UPDATE keyset_redemption_cutoff_requests
            SET status = ?, decided_by = ?, decided_at = ?, outcome = ?
            WHERE request_id = ? AND status = ?
        """;

    private static final String SELECT_BY_ID_SQL =
        "SELECT " + COLUMNS + " FROM keyset_redemption_cutoff_requests WHERE request_id = ?";

    private static final String SELECT_BY_MINT_SQL =
        "SELECT " + COLUMNS + " FROM keyset_redemption_cutoff_requests WHERE mint_id = ? ORDER BY requested_at DESC";

    private static final String INSERT_AUDIT_SQL =
        """
            INSERT INTO keyset_redemption_cutoff_audit
                (request_id, mint_id, keyset_id, action, actor, occurred_at, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SELECT_AUDIT_SQL =
        """
            SELECT request_id, mint_id, keyset_id, action, actor, occurred_at, detail
            FROM keyset_redemption_cutoff_audit
            ORDER BY occurred_at DESC, entry_id DESC
        """;

    private final DataSource dataSource;

    public JdbcRedemptionCutoffRequestRepository(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void create(final CutoffRequest request) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setObject(1, request.requestId());
            statement.setObject(2, UUID.fromString(request.mintId().asString()));
            statement.setString(3, request.keysetId());
            statement.setTimestamp(4, Timestamp.from(request.redeemUntil()));
            statement.setString(5, request.reason());
            statement.setString(6, request.requestedBy());
            statement.setTimestamp(7, Timestamp.from(request.requestedAt()));
            statement.setString(8, request.status().name());
            statement.setString(9, request.decidedBy());
            statement.setTimestamp(10, timestamp(request.decidedAt()));
            statement.setString(11, request.outcome());
            statement.executeUpdate();
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to create redemption cutoff request", ex);
        }
    }

    @Override
    public boolean transition(final UUID requestId, final Status expected, final Status next,
                              final String decidedBy, final Instant decidedAt, final String outcome) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TRANSITION_SQL)) {
            statement.setString(1, next.name());
            statement.setString(2, decidedBy);
            statement.setTimestamp(3, timestamp(decidedAt));
            statement.setString(4, outcome);
            statement.setObject(5, requestId);
            statement.setString(6, expected.name());
            return statement.executeUpdate() == 1;
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to transition redemption cutoff request", ex);
        }
    }

    @Override
    public Optional<CutoffRequest> findById(final UUID requestId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_SQL)) {
            statement.setObject(1, requestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRequest(resultSet)) : Optional.empty();
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load redemption cutoff request", ex);
        }
    }

    @Override
    public List<CutoffRequest> findByMintId(final MintId mintId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_MINT_SQL)) {
            statement.setObject(1, UUID.fromString(mintId.asString()));
            try (ResultSet resultSet = statement.executeQuery()) {
                final List<CutoffRequest> found = new ArrayList<>();
                while (resultSet.next()) {
                    found.add(toRequest(resultSet));
                }
                return found;
            }
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to list redemption cutoff requests", ex);
        }
    }

    @Override
    public void audit(final AuditEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_AUDIT_SQL)) {
            statement.setObject(1, entry.requestId());
            statement.setObject(2, UUID.fromString(entry.mintId().asString()));
            statement.setString(3, entry.keysetId());
            statement.setString(4, entry.action());
            statement.setString(5, entry.actor());
            statement.setTimestamp(6, Timestamp.from(entry.occurredAt()));
            statement.setString(7, entry.detail());
            statement.executeUpdate();
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to record redemption cutoff audit entry", ex);
        }
    }

    @Override
    public List<AuditEntry> findAudit() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_AUDIT_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            final List<AuditEntry> entries = new ArrayList<>();
            while (resultSet.next()) {
                entries.add(new AuditEntry(
                    resultSet.getObject("request_id", UUID.class),
                    MintId.of(resultSet.getObject("mint_id", UUID.class)),
                    resultSet.getString("keyset_id"),
                    resultSet.getString("action"),
                    resultSet.getString("actor"),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    resultSet.getString("detail")));
            }
            return entries;
        } catch (final SQLException ex) {
            throw new JdbcRepositoryException("Failed to load redemption cutoff audit trail", ex);
        }
    }

    private static CutoffRequest toRequest(final ResultSet resultSet) throws SQLException {
        final Timestamp decidedAt = resultSet.getTimestamp("decided_at");
        return new CutoffRequest(
            resultSet.getObject("request_id", UUID.class),
            MintId.of(resultSet.getObject("mint_id", UUID.class)),
            resultSet.getString("keyset_id"),
            resultSet.getTimestamp("redeem_until").toInstant(),
            resultSet.getString("reason"),
            resultSet.getString("requested_by"),
            resultSet.getTimestamp("requested_at").toInstant(),
            Status.valueOf(resultSet.getString("status")),
            resultSet.getString("decided_by"),
            decidedAt == null ? null : decidedAt.toInstant(),
            resultSet.getString("outcome"));
    }

    private static Timestamp timestamp(final Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
