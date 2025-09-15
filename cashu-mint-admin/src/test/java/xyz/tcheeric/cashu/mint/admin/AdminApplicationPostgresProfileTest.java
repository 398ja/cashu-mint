package xyz.tcheeric.cashu.mint.admin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import xyz.tcheeric.cashu.mint.admin.config.properties.MintAdminProperties;

@SpringBootTest
@ActiveProfiles("postgres")
class AdminApplicationPostgresProfileTest {

    @DynamicPropertySource
    static void overrideDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:mint_admin_postgres;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MintAdminProperties properties;

    // Verifies the postgres profile boots even when redirected to an in-memory database for automated testing.
    @Test
    void shouldLoadContextWithPostgresProfile() {
        assertThat(dataSource).isNotNull();
        assertThat(properties.getMigrations().getFlyway().isRepairOnMigrate()).isTrue();
    }
}
