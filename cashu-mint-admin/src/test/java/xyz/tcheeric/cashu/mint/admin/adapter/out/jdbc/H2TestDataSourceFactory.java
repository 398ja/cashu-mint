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

    private static String readSchemaScript() throws IOException {
        final String v1 = readMigration("/db/migration/V1__create_admin_schema.sql");
        final String v2 = readMigration("/db/migration/V2__link_audit_events.sql");
        final String v3 = readMigration("/db/migration/V3__extend_audit_metadata.sql");
        return (v1 + "\n" + v2 + "\n" + v3)
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
