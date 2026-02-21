package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository.AlertRecord;

class JdbcAlertRepositoryIntegrationTest {

    private JdbcAlertRepository repository;

    @BeforeEach
    void setUp() {
        final DataSource dataSource = H2TestDataSourceFactory.createDataSource();
        repository = new JdbcAlertRepository(dataSource, new ObjectMapper());
    }

    // Verifies alerts and escalation records are persisted and reconstructed from JDBC storage.
    @Test
    void shouldPersistAlertAndEscalations() {
        final AlertRecord created = new AlertRecord(
            "alert-001",
            "mint-001",
            "CRITICAL",
            "Mint offline",
            Map.of("region", "us-east"),
            false,
            false,
            null,
            List.of());

        assertThat(repository.create(created)).isTrue();
        repository.appendEscalation("alert-001", "pagerduty");
        repository.appendEscalation("alert-001", "slack");

        final AlertRecord loaded = repository.findById("alert-001").orElseThrow();
        assertThat(loaded.alertId()).isEqualTo("alert-001");
        assertThat(loaded.labels()).containsEntry("region", "us-east");
        assertThat(loaded.escalations()).containsExactly("pagerduty", "slack");
    }

    // Ensures acknowledgement and silence flags are updated in persistent alert state.
    @Test
    void shouldUpdateAlertStateFlags() {
        repository.create(new AlertRecord(
            "alert-002",
            "mint-002",
            "WARNING",
            "High latency",
            Map.of(),
            false,
            false,
            null,
            List.of()));

        repository.update(new AlertRecord(
            "alert-002",
            "mint-002",
            "WARNING",
            "High latency",
            Map.of(),
            true,
            true,
            15,
            List.of()));

        final AlertRecord loaded = repository.findById("alert-002").orElseThrow();
        assertThat(loaded.acknowledged()).isTrue();
        assertThat(loaded.silenced()).isTrue();
        assertThat(loaded.silenceMinutes()).isEqualTo(15);
    }
}
