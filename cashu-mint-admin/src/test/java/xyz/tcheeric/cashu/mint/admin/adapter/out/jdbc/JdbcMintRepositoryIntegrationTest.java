package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;

class JdbcMintRepositoryIntegrationTest {

    private DataSource dataSource;
    private JdbcConfigurationSetRepository configurationRepository;
    private JdbcMintRepository mintRepository;
    private MintId mintId;

    @BeforeEach
    void setUp() {
        dataSource = H2TestDataSourceFactory.createDataSource();
        final ObjectMapper objectMapper = new ObjectMapper();
        configurationRepository = new JdbcConfigurationSetRepository(dataSource, objectMapper);
        mintRepository = new JdbcMintRepository(dataSource, configurationRepository, objectMapper);
        mintId = MintId.of(UUID.randomUUID());
    }

    // Validates that a mint aggregate can be stored and fully reconstructed from the database.
    @Test
    void shouldPersistAndLoadMintAggregate() {
        final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final AuditMetadata creationAudit = new AuditMetadata("system", "provision-mint", createdAt,
            List.of("initial-provision"), List.of("INC-100"), AutomationContext.manual());
        final ConfigurationSet configuration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("name", "TestMint"), creationAudit);
        final OperatorAccount operator = new OperatorAccount(UUID.randomUUID(), "Operator One", Set.of("ADMIN"),
            creationAudit);
        final NotificationPolicy policy = new NotificationPolicy(true, false, Duration.ofMinutes(15), creationAudit);
        final MintAggregate aggregate = MintAggregate.create(mintId, configuration, operator, policy, creationAudit);

        mintRepository.save(aggregate);

        final Optional<MintAggregate> loaded = mintRepository.findById(mintId);

        assertThat(loaded).isPresent();
        final MintAggregate reconstituted = loaded.orElseThrow();
        assertThat(reconstituted.mintId()).isEqualTo(mintId);
        assertThat(reconstituted.lifecycleState()).isEqualTo(aggregate.lifecycleState());
        assertThat(reconstituted.configurationSet()).isEqualTo(configuration);
        assertThat(reconstituted.operatorAccount()).isEqualTo(operator);
        assertThat(reconstituted.notificationPolicy()).isEqualTo(policy);
        assertThat(reconstituted.auditTrail()).isEqualTo(aggregate.auditTrail());
        assertThat(reconstituted.auditTrail().latestLifecycleContext().configurationRevisionId())
            .isEqualTo(configuration.revisionId());
        assertThat(reconstituted.auditTrail().latestLifecycleContext().notificationPolicySnapshot()).isNotNull();
        assertThat(reconstituted.auditTrail().latestReasonCodes()).containsExactly("initial-provision");
        assertThat(reconstituted.auditTrail().latestTicketReferences()).containsExactly("INC-100");
        assertThat(reconstituted.auditTrail().latestAutomationContext()).isEqualTo(AutomationContext.manual());

        final List<MintAggregate> all = mintRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).mintId()).isEqualTo(mintId);
    }

    // Confirms that updating an aggregate persists new state and replaces dependent records atomically.
    @Test
    void shouldUpdateExistingAggregateState() {
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final AuditMetadata creationAudit = new AuditMetadata("system", "provision-mint", baseTime,
            List.of("initial-provision"), List.of("INC-200"), AutomationContext.manual());
        final ConfigurationSet initialConfig = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("name", "TestMint"), creationAudit);
        final OperatorAccount initialOperator = new OperatorAccount(UUID.randomUUID(), "Operator One",
            Set.of("ADMIN"), creationAudit);
        final NotificationPolicy initialPolicy = new NotificationPolicy(true, false, Duration.ofMinutes(15),
            creationAudit);
        final MintAggregate aggregate = MintAggregate.create(mintId, initialConfig, initialOperator, initialPolicy,
            creationAudit);

        mintRepository.save(aggregate);

        final AuditMetadata configAudit = new AuditMetadata("system", "update-config", baseTime.plusSeconds(10),
            List.of("config-change"), List.of("INC-201"), new AutomationContext(true, "pipeline", "run-config"));
        final ConfigurationSet updatedConfig = initialConfig.updateParameter("fee", "0.5",
            ConfigurationRevisionId.of(2), configAudit);

        final AuditMetadata operatorAudit = new AuditMetadata("system", "update-operator", baseTime.plusSeconds(20),
            List.of("operator-update"), List.of("INC-202"), AutomationContext.manual());
        final OperatorAccount updatedOperator = new OperatorAccount(initialOperator.operatorId(), "Operator Two",
            Set.of("ADMIN", "AUDITOR"), operatorAudit);

        final AuditMetadata policyAudit = new AuditMetadata("system", "update-policy", baseTime.plusSeconds(30),
            List.of("policy-update"), List.of("INC-203"), AutomationContext.manual());
        final NotificationPolicy updatedPolicy = new NotificationPolicy(true, true, Duration.ofMinutes(5),
            policyAudit);

        final AuditMetadata activationAudit = new AuditMetadata("system", "activate-mint", baseTime.plusSeconds(40),
            List.of("activate"), List.of("INC-204"), new AutomationContext(true, "orchestrator", "run-activation"));

        MintAggregate updatedAggregate = aggregate.updateConfiguration(updatedConfig, configAudit);
        updatedAggregate = updatedAggregate.updateOperatorAccount(updatedOperator, operatorAudit);
        updatedAggregate = updatedAggregate.updateNotificationPolicy(updatedPolicy, policyAudit);
        updatedAggregate = updatedAggregate.activate(activationAudit);

        mintRepository.save(updatedAggregate);

        final MintAggregate reloaded = mintRepository.findById(mintId).orElseThrow();
        assertThat(reloaded.lifecycleState().value()).isEqualTo(LifecycleState.State.ACTIVE);
        assertThat(reloaded.configurationSet()).isEqualTo(updatedConfig);
        assertThat(reloaded.operatorAccount()).isEqualTo(updatedOperator);
        assertThat(reloaded.notificationPolicy()).isEqualTo(updatedPolicy);
        assertThat(reloaded.auditMetadata().actor()).isEqualTo(activationAudit.actor());
        assertThat(reloaded.auditMetadata().action()).isEqualTo(activationAudit.action());
        assertThat(reloaded.auditMetadata().timestamp()).isEqualTo(activationAudit.timestamp());
        assertThat(reloaded.auditTrail()).isEqualTo(updatedAggregate.auditTrail());
        assertThat(reloaded.auditTrail().latestLifecycleContext().configurationRevisionId())
            .isEqualTo(updatedConfig.revisionId());
        assertThat(reloaded.auditTrail().latestLifecycleContext().notificationPolicySnapshot()).isNotNull();
        assertThat(reloaded.auditTrail().latestReasonCodes()).containsExactly("activate");
        assertThat(reloaded.auditTrail().latestTicketReferences()).containsExactly("INC-204");
        assertThat(reloaded.auditTrail().latestAutomationContext())
            .isEqualTo(new AutomationContext(true, "orchestrator", "run-activation"));

        final List<MintAggregate> all = mintRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).auditTrail().entries()).hasSize(updatedAggregate.auditTrail().entries().size());
    }
}
