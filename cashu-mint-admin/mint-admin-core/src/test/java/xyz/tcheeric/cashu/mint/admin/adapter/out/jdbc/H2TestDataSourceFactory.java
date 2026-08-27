package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

final class H2TestDataSourceFactory {

    private static final String URL_TEMPLATE =
        "jdbc:h2:mem:cashu_admin_%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private H2TestDataSourceFactory() {
    }

    static DataSource createDataSource() {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(URL_TEMPLATE.formatted(UUID.randomUUID()));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        initializeSchema(dataSource);
        return new TranslatingDataSource(dataSource);
    }

    private static void initializeSchema(final DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            final String script = readSchemaScript();
            for (final String statement : script.split(";")) {
                final String trimmed = statement.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try (PreparedStatement prepared = connection.prepareStatement(trimmed)) {
                    prepared.execute();
                }
            }
        } catch (final SQLException | IOException ex) {
            throw new IllegalStateException("Failed to initialize in-memory admin schema", ex);
        }
    }

    // Every migration must be listed here, in order. A missing entry gives tests a
    // stale schema that differs from production.
    private static String readSchemaScript() throws IOException {
        final String v1 = readMigration("/db/migration-admin/V1__create_admin_schema.sql");
        final String v2 = readMigration("/db/migration-admin/V2__link_audit_events.sql");
        final String v3 = readMigration("/db/migration-admin/V3__extend_audit_metadata.sql");
        final String v4 = readMigration("/db/migration-admin/V4__create_mint_lifecycle_history.sql");
        final String v5 = readMigration("/db/migration-admin/V5__create_mint_lifecycle_approval_states.sql");
        final String v6 = readMigration("/db/migration-admin/V6__extend_lifecycle_audit_linkage.sql");
        final String v7 = readMigration("/db/migration-admin/V7__create_admin_user_accounts.sql");
        final String v8 = readMigration("/db/migration-admin/V8__create_alerts_and_alert_escalations.sql");
        final String v9 = readMigration("/db/migration-admin/V9__create_mint_health_snapshots.sql");
        final String v10 = readMigration("/db/migration-admin/V10__create_operational_controls.sql");
        final String v11 = readMigration("/db/migration-admin/V11__drop_health_and_alert_tables.sql");
        final String v12 = readMigration("/db/migration-admin/V12__operator_credential_hash.sql");
        final String v13 = readMigration("/db/migration-admin/V13__operational_control_outcome.sql");
        final String v14 = readMigration("/db/migration-admin/V14__operator_pubkey.sql");
        final String v15 = readMigration("/db/migration-admin/V15__operator_nap_identity_only.sql");
        return (v1 + "\n" + v2 + "\n" + v3 + "\n" + v4 + "\n" + v5 + "\n" + v6
            + "\n" + v7 + "\n" + v8 + "\n" + v9 + "\n" + v10 + "\n" + v11 + "\n" + v12 + "\n" + v13
            + "\n" + v14 + "\n" + v15)
            .replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
            .replace("    WHERE dispatched_at IS NULL", "");
    }

    private static String readMigration(final String resource) throws IOException {
        try (InputStream inputStream = H2TestDataSourceFactory.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to locate admin schema migration script: " + resource);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
