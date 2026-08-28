package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;

class JdbcOperatorAccessRepositoryIntegrationTest {

    private static final String PUBKEY = "e8b487c079b0f67c695ae6c4c2552a47f38adfa2533cc5926bd2c102942fdcb7";

    private JdbcOperatorAccessRepository repository;

    @BeforeEach
    void setUp() {
        final DataSource dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcOperatorAccessRepository(dataSource, new ObjectMapper());
    }

    // Verifies operator accounts inserted here can be loaded back with their persisted role data.
    @Test
    void shouldCreateAndLoadOperatorAccount() {
        final OperatorAccessAccount account = new OperatorAccessAccount(
            "user-001",
            "Alice Ops",
            "alice@example.com",
            Set.of("ADMIN", "AUDITOR"),
            true,
            PUBKEY);

        final boolean created = repository.create(account);
        final Optional<OperatorAccessAccount> loaded = repository.findById("user-001");

        assertThat(created).isTrue();
        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().roles()).containsExactlyInAnyOrder("ADMIN", "AUDITOR");
        assertThat(loaded.orElseThrow().active()).isTrue();
        assertThat(loaded.orElseThrow().pubkey()).isEqualTo(PUBKEY);
    }

    // Ensures account updates persist the new roles and active status.
    @Test
    void shouldUpdateOperatorAccountState() {
        repository.create(new OperatorAccessAccount(
            "user-002", "Bob Ops", "bob@example.com", Set.of("ADMIN"), true, PUBKEY));

        repository.update(new OperatorAccessAccount(
            "user-002", "Bob Ops", "bob@example.com", Set.of("VIEWER"), false, PUBKEY));

        final OperatorAccessAccount reloaded = repository.findById("user-002").orElseThrow();
        assertThat(reloaded.roles()).containsExactly("VIEWER");
        assertThat(reloaded.active()).isFalse();
        assertThat(reloaded.pubkey()).isEqualTo(PUBKEY);
    }

    // Verifies an operator is found by the Nostr public key NAP reports -- whatever
    // case it was stored in -- and that a blank key reaches nobody.
    @Test
    void shouldFindOperatorByPubkey() {
        repository.create(new OperatorAccessAccount(
            "user-003",
            "Carol Ops",
            "carol@example.com",
            Set.of("MINT_ADMIN"),
            true,
            PUBKEY.toUpperCase()));

        assertThat(repository.findByPubkey(PUBKEY).orElseThrow().accountId()).isEqualTo("user-003");
        assertThat(repository.findByPubkey(PUBKEY.toUpperCase()).orElseThrow().accountId())
            .isEqualTo("user-003");
        assertThat(repository.findByPubkey(null)).isEmpty();
        assertThat(repository.findByPubkey(" ")).isEmpty();
    }
}
