package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository.OperationalControlRecord;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository.OperationalControlType;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class JdbcOperationalControlRepositoryIntegrationTest {

    private JdbcOperationalControlRepository repository;
    private MintId mintId;
    private UUID operatorId;

    @BeforeEach
    void setUp() {
        final DataSource dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcOperationalControlRepository(dataSource);
        mintId = MintId.of(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        operatorId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    }

    // Verifies scheduled maintenance controls are discoverable as active windows.
    @Test
    void shouldPersistAndFindActiveMaintenance() {
        final OperationalControlRecord scheduled = new OperationalControlRecord(
            UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
            mintId,
            operatorId,
            OperationalControlType.MAINTENANCE,
            "SCHEDULED",
            Instant.parse("2026-02-15T03:00:00Z"),
            "planned",
            45);

        repository.create(scheduled);

        final OperationalControlRecord active = repository.findActiveMaintenanceByMintId(mintId).orElseThrow();
        assertThat(active.controlId()).isEqualTo(scheduled.controlId());
        assertThat(active.status()).isEqualTo("SCHEDULED");
    }

    // Ensures updates are reflected when looking up the current maintenance control.
    @Test
    void shouldUpdateMaintenanceStatus() {
        final String controlId = UUID.fromString("00000000-0000-0000-0000-000000000002").toString();
        repository.create(new OperationalControlRecord(
            controlId,
            mintId,
            operatorId,
            OperationalControlType.MAINTENANCE,
            "SCHEDULED",
            Instant.parse("2026-02-15T03:10:00Z"),
            "planned",
            30));

        repository.update(new OperationalControlRecord(
            controlId,
            mintId,
            operatorId,
            OperationalControlType.MAINTENANCE,
            "IN_PROGRESS",
            Instant.parse("2026-02-15T03:10:00Z"),
            "started",
            30));

        final OperationalControlRecord active = repository.findActiveMaintenanceByMintId(mintId).orElseThrow();
        assertThat(active.status()).isEqualTo("IN_PROGRESS");
        assertThat(active.reason()).isEqualTo("started");
    }
}
