package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;

class JdbcOperatorAccessRepositoryIntegrationTest {

    private JdbcOperatorAccessRepository repository;

    @BeforeEach
    void setUp() {
        final DataSource dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcOperatorAccessRepository(dataSource, new ObjectMapper());
    }

    // Verifies operator accounts are inserted and can be loaded with persisted role data.
    @Test
    void shouldCreateAndLoadOperatorAccount() {
        final OperatorAccessAccount account = new OperatorAccessAccount(
            "user-001",
            "Alice Ops",
            "alice@example.com",
            Set.of("ADMIN", "AUDITOR"),
            true,
            0,
            null,
            null);

        final boolean created = repository.create(account);
        final Optional<OperatorAccessAccount> loaded = repository.findById("user-001");

        assertThat(created).isTrue();
        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().roles()).containsExactlyInAnyOrder("ADMIN", "AUDITOR");
        assertThat(loaded.orElseThrow().active()).isTrue();
    }

    // Ensures account updates persist active status and reset metadata.
    @Test
    void shouldUpdateOperatorAccountState() {
        final OperatorAccessAccount account = new OperatorAccessAccount(
            "user-002",
            "Bob Ops",
            "bob@example.com",
            Set.of("ADMIN"),
            true,
            0,
            null,
            null);
        repository.create(account);

        final OperatorAccessAccount updated = new OperatorAccessAccount(
            "user-002",
            "Bob Ops",
            "bob@example.com",
            Set.of("VIEWER"),
            false,
            1,
            "user-002-reset-1",
            Instant.parse("2026-02-15T00:30:00Z"));
        repository.update(updated);

        final OperatorAccessAccount reloaded = repository.findById("user-002").orElseThrow();
        assertThat(reloaded.roles()).containsExactly("VIEWER");
        assertThat(reloaded.active()).isFalse();
        assertThat(reloaded.credentialResetCount()).isEqualTo(1);
        assertThat(reloaded.credentialHash()).isNotNull();
    }
}
