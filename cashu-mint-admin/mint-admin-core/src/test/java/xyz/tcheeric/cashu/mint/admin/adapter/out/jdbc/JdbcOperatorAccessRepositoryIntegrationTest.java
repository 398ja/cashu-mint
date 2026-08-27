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
            Instant.parse("2026-02-15T00:30:00Z"),
            null);
        repository.update(updated);

        final OperatorAccessAccount reloaded = repository.findById("user-002").orElseThrow();
        assertThat(reloaded.roles()).containsExactly("VIEWER");
        assertThat(reloaded.active()).isFalse();
        assertThat(reloaded.credentialResetCount()).isEqualTo(1);
        assertThat(reloaded.credentialHash()).isNotNull();
    }

    // Verifies an operator is found by the Nostr public key NAP reports -- whatever
    // case it was stored in -- and that an operator without one is not reachable by
    // a blank key.
    @Test
    void shouldFindOperatorByPubkey() {
        final String pubkey = "e8b487c079b0f67c695ae6c4c2552a47f38adfa2533cc5926bd2c102942fdcb7";
        repository.create(new OperatorAccessAccount(
            "user-003",
            "Carol Ops",
            "carol@example.com",
            Set.of("MINT_ADMIN"),
            true,
            0,
            null,
            null,
            pubkey.toUpperCase()));
        repository.create(new OperatorAccessAccount(
            "user-004",
            "Dave Ops",
            "dave@example.com",
            Set.of("MINT_ADMIN"),
            true,
            0,
            null,
            null,
            null));

        assertThat(repository.findByPubkey(pubkey).orElseThrow().accountId()).isEqualTo("user-003");
        assertThat(repository.findByPubkey(pubkey.toUpperCase()).orElseThrow().accountId()).isEqualTo("user-003");
        assertThat(repository.findByPubkey(null)).isEmpty();
        assertThat(repository.findByPubkey(" ")).isEmpty();
    }
}
