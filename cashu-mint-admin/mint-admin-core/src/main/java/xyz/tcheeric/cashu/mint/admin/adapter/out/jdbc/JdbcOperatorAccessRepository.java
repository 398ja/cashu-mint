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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;

/**
 * JDBC implementation of {@link OperatorAccessRepository}.
 */
public class JdbcOperatorAccessRepository implements OperatorAccessRepository {

    private static final String INSERT_SQL =
        """
            INSERT INTO admin_users (
                user_id,
                display_name,
                email,
                roles,
                active,
                reset_count,
                reset_token,
                reset_requested_at,
                updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL =
        """
            UPDATE admin_users
            SET display_name = ?,
                email = ?,
                roles = ?,
                active = ?,
                reset_count = ?,
                reset_token = ?,
                reset_requested_at = ?,
                updated_at = ?
            WHERE user_id = ?
        """;

    private static final String SELECT_SQL =
        """
            SELECT user_id,
                   display_name,
                   email,
                   roles,
                   active,
                   reset_count,
                   reset_token,
                   reset_requested_at
            FROM admin_users
            WHERE user_id = ?
        """;

    private static final TypeReference<Set<String>> ROLE_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcOperatorAccessRepository(final DataSource dataSource, final ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    private static final String SELECT_ALL_SQL =
        """
            SELECT user_id,
                   display_name,
                   email,
                   roles,
                   active,
                   reset_count,
                   reset_token,
                   reset_requested_at
            FROM admin_users
            ORDER BY display_name
        """;

    @Override
    public List<OperatorAccessAccount> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            final List<OperatorAccessAccount> accounts = new ArrayList<>();
            while (resultSet.next()) {
                accounts.add(mapRow(resultSet));
            }
            return accounts;
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load admin users", ex);
        }
    }

    @Override
    public boolean create(final OperatorAccessAccount account) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindForCreate(statement, account);
            statement.executeUpdate();
            return true;
        } catch (final SQLException ex) {
            if (isUniqueViolation(ex)) {
                return false;
            }
            throw new JdbcRepositoryException("Failed to create admin user", ex);
        } catch (final IOException ex) {
            throw new JdbcRepositoryException("Failed to serialize admin user roles", ex);
        }
    }

    @Override
    public Optional<OperatorAccessAccount> findById(final String accountId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, accountId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(resultSet));
            }
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to load admin user", ex);
        }
    }

    @Override
    public void update(final OperatorAccessAccount account) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            bindForUpdate(statement, account);
            final int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new JdbcRepositoryException("Admin user not found for update: " + account.accountId());
            }
        } catch (final SQLException | IOException ex) {
            throw new JdbcRepositoryException("Failed to update admin user", ex);
        }
    }

    private void bindForCreate(final PreparedStatement statement, final OperatorAccessAccount account)
        throws SQLException, IOException {
        statement.setString(1, account.accountId());
        statement.setString(2, account.displayName());
        statement.setString(3, account.email());
        statement.setString(4, objectMapper.writeValueAsString(account.roles()));
        statement.setBoolean(5, account.active());
        statement.setInt(6, account.credentialResetCount());
        statement.setString(7, account.lastResetToken());
        if (account.lastResetAt() == null) {
            statement.setNull(8, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(8, Timestamp.from(account.lastResetAt()));
        }
        statement.setTimestamp(9, Timestamp.from(Instant.now()));
    }

    private void bindForUpdate(final PreparedStatement statement, final OperatorAccessAccount account)
        throws SQLException, IOException {
        statement.setString(1, account.displayName());
        statement.setString(2, account.email());
        statement.setString(3, objectMapper.writeValueAsString(account.roles()));
        statement.setBoolean(4, account.active());
        statement.setInt(5, account.credentialResetCount());
        statement.setString(6, account.lastResetToken());
        if (account.lastResetAt() == null) {
            statement.setNull(7, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(7, Timestamp.from(account.lastResetAt()));
        }
        statement.setTimestamp(8, Timestamp.from(Instant.now()));
        statement.setString(9, account.accountId());
    }

    private OperatorAccessAccount mapRow(final ResultSet resultSet) throws SQLException, IOException {
        final Set<String> roles = objectMapper.readValue(resultSet.getString("roles"), ROLE_TYPE);
        final Timestamp resetAt = resultSet.getTimestamp("reset_requested_at");
        return new OperatorAccessAccount(
            resultSet.getString("user_id"),
            resultSet.getString("display_name"),
            resultSet.getString("email"),
            roles,
            resultSet.getBoolean("active"),
            resultSet.getInt("reset_count"),
            resultSet.getString("reset_token"),
            resetAt != null ? resetAt.toInstant() : null);
    }

    private boolean isUniqueViolation(final SQLException ex) {
        return "23505".equals(ex.getSQLState());
    }
}
