package xyz.tcheeric.cashu.mint.admin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import xyz.tcheeric.cashu.mint.admin.config.properties.MintAdminProperties;

@SpringBootTest
@ActiveProfiles("h2")
class AdminApplicationH2ProfileTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MintAdminProperties properties;

    // Ensures the application context starts successfully using the dedicated H2 profile configuration.
    @Test
    void shouldLoadContextWithH2Profile() {
        assertThat(dataSource).isNotNull();
        assertThat(properties.getMigrations().getFlyway().isEnabled()).isTrue();
    }
}
