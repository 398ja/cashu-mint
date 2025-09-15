package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;

final class TestDatabaseFactory {

    private TestDatabaseFactory() {
    }

    static DataSource createDataSource() {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:cashu-admin-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("sa");
        return dataSource;
    }

    static Flyway migrate(final DataSource dataSource) {
        final Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
        return flyway;
    }
}
