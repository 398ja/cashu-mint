package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure.AbstractAdminIntegrationIT;

@Sql(scripts = "classpath:sql/truncate_admin_tables.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class HealthPersistenceIT extends AbstractAdminIntegrationIT {

    private static final String MINT_ID = "44444444-4444-4444-4444-444444444444";

    // Verifies persisted health snapshots are returned and acknowledge updates state in PostgreSQL.
    @Test
    @Order(1)
    void shouldPersistAndAcknowledgeHealthSnapshot() {
        jdbcTemplate.update(
            "INSERT INTO mint_health_snapshots (mint_id, health_status, lifecycle_state, checked_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)",
            UUID.fromString(MINT_ID),
            "CRITICAL",
            "ACTIVE",
            Timestamp.from(Instant.parse("2026-02-15T10:00:00Z")),
            Timestamp.from(Instant.parse("2026-02-15T10:00:00Z")));

        final ResponseEntity<JsonNode> snapshot = adminApiClient().get(
            "/admin/health/mints/" + MINT_ID,
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(snapshot.getStatusCode().value()).isEqualTo(200);
        assertThat(snapshot.getBody().path("status").asText()).isEqualTo("CRITICAL");

        final ResponseEntity<JsonNode> acknowledged = adminApiClient().post(
            "/admin/health/mints/" + MINT_ID + "/acknowledge",
            null,
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(acknowledged.getStatusCode().value()).isEqualTo(200);
        assertThat(acknowledged.getBody().path("status").asText()).isEqualTo("HEALTHY");

        final String persistedStatus = jdbcTemplate.queryForObject(
            "SELECT health_status FROM mint_health_snapshots WHERE mint_id = ?",
            String.class,
            UUID.fromString(MINT_ID));
        assertThat(persistedStatus).isEqualTo("HEALTHY");

        final String missingMintId = UUID.randomUUID().toString();
        final ResponseEntity<JsonNode> missing = adminApiClient().post(
            "/admin/health/mints/" + missingMintId + "/acknowledge",
            null,
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().path("code").asText()).isEqualTo("mint_not_found");
    }

    // Confirms acknowledged health state remains available after Spring context restart.
    @Test
    @Order(2)
    void shouldRetainAcknowledgedHealthStateAcrossContextRestart() {
        final ResponseEntity<JsonNode> snapshot = adminApiClient().get(
            "/admin/health/mints/" + MINT_ID,
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);

        assertThat(snapshot.getStatusCode().value()).isEqualTo(200);
        assertThat(snapshot.getBody().path("status").asText()).isEqualTo("HEALTHY");
    }
}
