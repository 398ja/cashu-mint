package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository.MintHealthSnapshot;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository.MintHealthStatus;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class JdbcMintHealthRepositoryIntegrationTest {

    private JdbcMintHealthRepository repository;
    private MintId mintId;

    @BeforeEach
    void setUp() {
        final DataSource dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcMintHealthRepository(dataSource);
        mintId = MintId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    // Verifies a persisted health snapshot can be loaded for the same mint identifier.
    @Test
    void shouldPersistAndLoadSnapshot() {
        final MintHealthSnapshot snapshot = new MintHealthSnapshot(
            mintId,
            MintHealthStatus.WARNING,
            "ACTIVE",
            Instant.parse("2026-02-15T02:00:00Z"));

        repository.upsert(snapshot);

        final MintHealthSnapshot loaded = repository.findByMintId(mintId).orElseThrow();
        assertThat(loaded.status()).isEqualTo(MintHealthStatus.WARNING);
        assertThat(loaded.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(loaded.checkedAt()).isEqualTo(Instant.parse("2026-02-15T02:00:00Z"));
    }

    // Ensures upsert overwrites previous health status and timestamp for the same mint.
    @Test
    void shouldOverwriteSnapshotOnUpsert() {
        repository.upsert(new MintHealthSnapshot(
            mintId,
            MintHealthStatus.CRITICAL,
            "ACTIVE",
            Instant.parse("2026-02-15T02:10:00Z")));
        repository.upsert(new MintHealthSnapshot(
            mintId,
            MintHealthStatus.HEALTHY,
            "ACTIVE",
            Instant.parse("2026-02-15T02:20:00Z")));

        final MintHealthSnapshot loaded = repository.findByMintId(mintId).orElseThrow();
        assertThat(loaded.status()).isEqualTo(MintHealthStatus.HEALTHY);
        assertThat(loaded.checkedAt()).isEqualTo(Instant.parse("2026-02-15T02:20:00Z"));
    }
}
