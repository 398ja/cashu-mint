package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleContext;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class JdbcMintLifecycleHistoryRepositoryIntegrationTest {

    private DataSource dataSource;
    private JdbcMintLifecycleHistoryRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcMintLifecycleHistoryRepository(dataSource, new ObjectMapper());
    }

    // Ensures lifecycle events are recorded with ordering guarantees and can be reconstructed.
    @Test
    void shouldPersistAndRetrieveLifecycleHistory() {
        final MintId mintId = MintId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        final MintLifecycleEvent provisioned = MintLifecycleEvent.created(mintId,
            LifecycleState.State.PROVISIONED,
            ConfigurationRevisionId.of(1),
            "v1",
            new AuditMetadata("system", "create", baseTime,
                List.of("initial"), List.of("INC-1"), AutomationContext.manual(), LifecycleContext.empty(),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "corr-create"));
        repository.append(UUID.fromString("00000000-0000-0000-0000-000000000001"), provisioned);

        final MintLifecycleEvent paused = MintLifecycleEvent.paused(mintId,
            LifecycleState.State.ACTIVE,
            LifecycleState.State.SUSPENDED,
            ConfigurationRevisionId.of(1),
            "v1",
            new AuditMetadata("ops", "pause", baseTime.plusSeconds(5),
                List.of(), List.of(), new AutomationContext(true, "scheduler", "pause-run"),
                LifecycleContext.empty(), UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), "corr-pause"));
        repository.append(UUID.fromString("00000000-0000-0000-0000-000000000002"), paused);

        final MintLifecycleEvent resumed = MintLifecycleEvent.resumed(mintId,
            LifecycleState.State.SUSPENDED,
            LifecycleState.State.ACTIVE,
            ConfigurationRevisionId.of(2),
            "v2",
            new AuditMetadata("ops", "resume", baseTime.plusSeconds(10),
                List.of("maintenance"), List.of(), AutomationContext.manual(), LifecycleContext.empty(),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174002"), "corr-resume"));
        repository.append(UUID.fromString("00000000-0000-0000-0000-000000000003"), resumed);

        final MintId otherMint = MintId.of(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        final MintLifecycleEvent otherEvent = MintLifecycleEvent.created(otherMint,
            LifecycleState.State.PROVISIONED,
            ConfigurationRevisionId.of(1),
            "other",
            new AuditMetadata("system", "create", baseTime.plusSeconds(1),
                List.of(), List.of(), AutomationContext.manual()));
        repository.append(UUID.fromString("00000000-0000-0000-0000-000000000004"), otherEvent);

        final List<MintLifecycleHistoryRepository.MintLifecycleHistoryEntry> history = repository.findByMintId(mintId);
        assertThat(history).hasSize(3);
        assertThat(history).extracting(entry -> entry.event().type())
            .containsExactly(
                MintLifecycleEvent.MintLifecycleEventType.CREATED,
                MintLifecycleEvent.MintLifecycleEventType.PAUSED,
                MintLifecycleEvent.MintLifecycleEventType.RESUMED);
        assertThat(history.get(0).event().auditMetadata().reasonCodes()).containsExactly("initial");
        assertThat(history.get(0).event().auditMetadata().requestId())
            .isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        assertThat(history.get(0).event().auditMetadata().correlationId()).isEqualTo("corr-create");
        assertThat(history.get(1).event().auditMetadata().automationContext().system()).isEqualTo("scheduler");
        assertThat(history.get(1).event().auditMetadata().requestId())
            .isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174001"));
        assertThat(history.get(2).event().configurationRevisionId()).isEqualTo(ConfigurationRevisionId.of(2));
        assertThat(history.get(2).event().auditMetadata().correlationId()).isEqualTo("corr-resume");
    }
}
