package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class JdbcConfigurationSetRepositoryIntegrationTest {

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private JdbcConfigurationSetRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = TestDatabaseFactory.createDataSource();
        TestDatabaseFactory.migrate(dataSource);
        objectMapper = new ObjectMapper();
        repository = new JdbcConfigurationSetRepository(dataSource, objectMapper);
    }

    // Ensures configuration revisions are stored and retrieved in the expected order.
    @Test
    void shouldPersistAndLoadConfigurationHistory() {
        final MintId mintId = MintId.of(UUID.randomUUID());
        final AuditMetadata auditOne = new AuditMetadata("alice", "create-config", Instant.parse("2024-03-01T00:00:00Z"));
        final ConfigurationSet revisionOne = new ConfigurationSet(ConfigurationRevisionId.of(1L),
            Map.of("currency", "USD"), auditOne);
        repository.save(mintId, revisionOne);

        final AuditMetadata auditTwo = new AuditMetadata("bob", "update-config", Instant.parse("2024-03-02T00:00:00Z"));
        final ConfigurationSet revisionTwo = new ConfigurationSet(ConfigurationRevisionId.of(2L),
            Map.of("currency", "USD", "fee", "0.0005"), auditTwo);
        repository.save(mintId, revisionTwo);

        final List<ConfigurationSet> history = repository.findByMintId(mintId);
        assertThat(history).extracting(ConfigurationSet::revisionId).containsExactly(
            ConfigurationRevisionId.of(1L), ConfigurationRevisionId.of(2L));

        assertThat(repository.findByRevision(mintId, revisionTwo.revisionId())).contains(revisionTwo);
    }
}
