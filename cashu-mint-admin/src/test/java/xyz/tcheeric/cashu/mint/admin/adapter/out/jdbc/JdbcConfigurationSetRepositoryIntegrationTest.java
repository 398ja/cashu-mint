package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class JdbcConfigurationSetRepositoryIntegrationTest {

    private MintId mintId;
    private JdbcConfigurationSetRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcConfigurationSetRepository(H2TestDataSourceFactory.createDataSource(), new ObjectMapper());
        mintId = MintId.of(UUID.randomUUID());
    }

    // Verifies that a configuration revision is stored and can be retrieved by its revision identifier.
    @Test
    void shouldPersistAndLoadConfigurationRevision() {
        final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final AuditMetadata audit = new AuditMetadata("alice", "create-config", createdAt);
        final ConfigurationSet configuration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("mint-name", "Atlantis"), audit);

        repository.save(mintId, configuration);

        final Optional<ConfigurationSet> loaded = repository.findByRevision(mintId, configuration.revisionId());

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow()).isEqualTo(configuration);
    }

    // Ensures the repository returns the complete configuration history ordered by revision number.
    @Test
    void shouldReturnAllRevisionsForMintInAscendingOrder() {
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final AuditMetadata revisionOneAudit = new AuditMetadata("alice", "create-config", baseTime);
        final ConfigurationSet revisionOne = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("mint-name", "Atlantis"), revisionOneAudit);

        final AuditMetadata revisionTwoAudit = new AuditMetadata("bob", "update-config", baseTime.plusSeconds(5));
        final ConfigurationSet revisionTwo = new ConfigurationSet(ConfigurationRevisionId.of(2),
            Map.of("mint-name", "Atlantis", "fee", "1.0"), revisionTwoAudit);

        repository.save(mintId, revisionOne);
        repository.save(mintId, revisionTwo);

        final List<ConfigurationSet> revisions = repository.findByMintId(mintId);

        assertThat(revisions).containsExactly(revisionOne, revisionTwo);
    }
}
