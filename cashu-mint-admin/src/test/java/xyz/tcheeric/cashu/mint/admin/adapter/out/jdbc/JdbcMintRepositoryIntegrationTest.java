package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;

class JdbcMintRepositoryIntegrationTest {

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private JdbcConfigurationSetRepository configurationRepository;
    private JdbcMintRepository mintRepository;

    @BeforeEach
    void setUp() {
        dataSource = TestDatabaseFactory.createDataSource();
        TestDatabaseFactory.migrate(dataSource);
        objectMapper = new ObjectMapper();
        configurationRepository = new JdbcConfigurationSetRepository(dataSource, objectMapper);
        mintRepository = new JdbcMintRepository(dataSource, configurationRepository, objectMapper);
    }

    // Ensures a mint aggregate survives a full round-trip through the JDBC repository.
    @Test
    void shouldPersistAndReloadMintAggregate() {
        final MintId mintId = MintId.of(UUID.randomUUID());
        final AuditMetadata created = new AuditMetadata("system", "provision", Instant.parse("2024-01-01T00:00:00Z"));
        final ConfigurationSet initialConfiguration = new ConfigurationSet(ConfigurationRevisionId.of(1L),
            Map.of("currency", "USD"), created);
        final OperatorAccount initialOperator = new OperatorAccount(UUID.randomUUID(), "Initial Operator",
            Set.of("ADMIN"), created);
        final NotificationPolicy initialPolicy = new NotificationPolicy(true, false, Duration.ofMinutes(5), created);
        MintAggregate aggregate = MintAggregate.create(mintId, initialConfiguration, initialOperator, initialPolicy, created);

        mintRepository.save(aggregate);

        final AuditMetadata activationAudit = new AuditMetadata("system", "activate",
            Instant.parse("2024-01-02T00:00:00Z"));
        aggregate = aggregate.activate(activationAudit);

        final AuditMetadata configAudit = new AuditMetadata("admin", "update-config",
            Instant.parse("2024-01-03T00:00:00Z"));
        final ConfigurationSet updatedConfiguration = new ConfigurationSet(ConfigurationRevisionId.of(2L),
            Map.of("currency", "USD", "fee", "0.001"), configAudit);
        aggregate = aggregate.updateConfiguration(updatedConfiguration, configAudit);

        final AuditMetadata operatorAudit = new AuditMetadata("admin", "update-operator",
            Instant.parse("2024-01-04T00:00:00Z"));
        final OperatorAccount updatedOperator = new OperatorAccount(initialOperator.operatorId(), "Updated Operator",
            Set.of("ADMIN", "SUPPORT"), operatorAudit);
        aggregate = aggregate.updateOperatorAccount(updatedOperator, operatorAudit);

        final AuditMetadata policyAudit = new AuditMetadata("admin", "update-policy",
            Instant.parse("2024-01-05T00:00:00Z"));
        final NotificationPolicy updatedPolicy = new NotificationPolicy(true, true, Duration.ofMinutes(1), policyAudit);
        aggregate = aggregate.updateNotificationPolicy(updatedPolicy, policyAudit);

        mintRepository.save(aggregate);

        final MintAggregate reloaded = mintRepository.findById(mintId).orElseThrow();
        assertThat(reloaded.mintId()).isEqualTo(aggregate.mintId());
        assertThat(reloaded.lifecycleState()).isEqualTo(aggregate.lifecycleState());
        assertThat(reloaded.configurationSet().revisionId()).isEqualTo(updatedConfiguration.revisionId());
        assertThat(reloaded.configurationSet().parameters()).isEqualTo(updatedConfiguration.parameters());
        assertThat(reloaded.operatorAccount().displayName()).isEqualTo(updatedOperator.displayName());
        assertThat(reloaded.operatorAccount().roles()).containsExactlyInAnyOrderElementsOf(updatedOperator.roles());
        assertThat(reloaded.notificationPolicy().emailEnabled()).isEqualTo(updatedPolicy.emailEnabled());
        assertThat(reloaded.notificationPolicy().webhookEnabled()).isEqualTo(updatedPolicy.webhookEnabled());
        assertThat(reloaded.notificationPolicy().throttleInterval()).isEqualTo(updatedPolicy.throttleInterval());
        assertThat(reloaded.auditTrail().entries()).hasSize(aggregate.auditTrail().entries().size());
        assertThat(reloaded.auditMetadata()).isEqualTo(aggregate.auditMetadata());

        final List<ConfigurationSet> history = configurationRepository.findByMintId(mintId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).revisionId()).isEqualTo(ConfigurationRevisionId.of(1L));
        assertThat(history.get(1).revisionId()).isEqualTo(ConfigurationRevisionId.of(2L));
    }

    // Ensures all persisted mint aggregates are returned when querying for every mint.
    @Test
    void shouldFindAllPersistedMints() {
        final MintAggregate first = createSimpleAggregate("alice", "2024-02-01T00:00:00Z");
        final MintAggregate second = createSimpleAggregate("bob", "2024-02-02T00:00:00Z");

        mintRepository.save(first);
        mintRepository.save(second);

        final List<MintAggregate> aggregates = mintRepository.findAll();
        assertThat(aggregates).extracting(mint -> mint.mintId().value()).containsExactlyInAnyOrder(
            first.mintId().value(), second.mintId().value());
    }

    private MintAggregate createSimpleAggregate(final String operatorName, final String timestamp) {
        final MintId mintId = MintId.of(UUID.randomUUID());
        final AuditMetadata audit = new AuditMetadata(operatorName, "provision", Instant.parse(timestamp));
        final ConfigurationSet configuration = new ConfigurationSet(ConfigurationRevisionId.of(1L),
            Map.of("currency", "EUR"), audit);
        final OperatorAccount operator = new OperatorAccount(UUID.randomUUID(), operatorName, Set.of("ADMIN"), audit);
        final NotificationPolicy policy = new NotificationPolicy(false, true, Duration.ofMinutes(10), audit);
        return MintAggregate.create(mintId, configuration, operator, policy, audit);
    }
}
