package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class JdbcMintAggregateViewRepositoryIntegrationTest {

    private DataSource dataSource;
    private JdbcMintAggregateViewRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcMintAggregateViewRepository(dataSource);
    }

    // Ensures lifecycle events update the projection table and can be queried afterwards.
    @Test
    void shouldPersistAndLoadAggregateSnapshots() {
        final MintId firstMint = MintId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        final MintId secondMint = MintId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        final MintLifecycleEvent created = MintLifecycleEvent.created(firstMint,
            LifecycleState.State.PROVISIONED,
            ConfigurationRevisionId.of(1),
            "v1",
            new AuditMetadata("system", "provision", baseTime,
                List.of("initial"), List.of(), AutomationContext.manual()));
        repository.upsert(created);

        final MintLifecycleEvent activated = MintLifecycleEvent.resumed(firstMint,
            LifecycleState.State.SUSPENDED,
            LifecycleState.State.ACTIVE,
            ConfigurationRevisionId.of(2),
            "v2",
            new AuditMetadata("system", "resume", baseTime.plusSeconds(10),
                List.of(), List.of("INC-123"), new AutomationContext(true, "pipeline", "run-42")));
        repository.upsert(activated);

        final MintLifecycleEvent otherMint = MintLifecycleEvent.created(secondMint,
            LifecycleState.State.PROVISIONED,
            ConfigurationRevisionId.of(5),
            "alpha",
            new AuditMetadata("ops", "create", baseTime.plusSeconds(20),
                List.of(), List.of(), AutomationContext.manual()));
        repository.upsert(otherMint);

        final MintAggregateViewRepository.MintAggregateView snapshot = repository.findById(firstMint).orElseThrow();
        assertThat(snapshot.mintId()).isEqualTo(firstMint);
        assertThat(snapshot.lifecycleState()).isEqualTo(LifecycleState.State.ACTIVE);
        assertThat(snapshot.configurationRevisionId()).isEqualTo(ConfigurationRevisionId.of(2));
        assertThat(snapshot.versionTag()).isEqualTo("v2");
        assertThat(snapshot.updatedAt()).isEqualTo(baseTime.plusSeconds(10));

        final List<MintAggregateViewRepository.MintAggregateView> all = repository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all.stream().map(MintAggregateViewRepository.MintAggregateView::mintId))
            .containsExactly(firstMint, secondMint);
    }
}
