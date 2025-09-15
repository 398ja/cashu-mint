package xyz.tcheeric.cashu.mint.admin.config.properties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mint.admin")
public class MintAdminProperties {

    private final MigrationProperties migrations = new MigrationProperties();

    @Data
    public static class MigrationProperties {
        private final FlywayProperties flyway = new FlywayProperties();
        private final LiquibaseProperties liquibase = new LiquibaseProperties();
    }

    @Data
    public static class FlywayProperties {
        private boolean enabled = true;
        private boolean cleanBeforeMigrate = false;
        private boolean repairOnMigrate = true;
        private List<String> locations = new ArrayList<>(Collections.singletonList("classpath:db/migration/admin"));
    }

    @Data
    public static class LiquibaseProperties {
        private boolean enabled = false;
        private String changeLog = "classpath:db/changelog/db.changelog-master.yaml";
        private String defaultSchema;
        private List<String> contexts = new ArrayList<>();
        private boolean dropFirst = false;
    }
}
