package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessAuditRepository.OperatorAccessAuditEntry;

class JdbcOperatorAccessAuditRepositoryIntegrationTest {

    private static final String ACTOR = "123e4567-e89b-12d3-a456-426614174000";
    private static final String TARGET = "123e4567-e89b-12d3-a456-426614174001";

    private JdbcOperatorAccessAuditRepository repository;

    @BeforeEach
    void setUp() {
        final DataSource dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcOperatorAccessAuditRepository(dataSource);
    }

    // Verifies a recorded action survives the round trip naming the operator who took it.
    @Test
    void shouldRecordAndLoadAnAction() {
        final Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        repository.record(new OperatorAccessAuditEntry(ACTOR, "REVOKE", TARGET, occurredAt));

        assertThat(repository.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.actor()).isEqualTo(ACTOR);
            assertThat(entry.action()).isEqualTo("REVOKE");
            assertThat(entry.targetAccountId()).isEqualTo(TARGET);
            assertThat(entry.occurredAt()).isEqualTo(occurredAt);
        });
    }

    // Verifies the trail is append-only rather than a current-state row: a suspension and the
    // reinstatement that followed it both stay readable, newest first.
    @Test
    void shouldKeepEveryActionNewestFirst() {
        final Instant earlier = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        repository.record(new OperatorAccessAuditEntry(ACTOR, "REVOKE", TARGET, earlier));
        repository.record(new OperatorAccessAuditEntry(ACTOR, "REINSTATE", TARGET, earlier.plusSeconds(60)));

        assertThat(repository.findAll()).extracting(OperatorAccessAuditEntry::action)
            .containsExactly("REINSTATE", "REVOKE");
    }

    // Verifies an empty trail reads as no actions rather than as a failure.
    @Test
    void shouldReturnAnEmptyTrailBeforeAnyAction() {
        assertThat(repository.findAll()).isEmpty();
    }
}
