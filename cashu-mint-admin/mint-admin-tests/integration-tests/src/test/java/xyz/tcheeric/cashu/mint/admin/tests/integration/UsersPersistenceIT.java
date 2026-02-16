package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
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
class UsersPersistenceIT extends AbstractAdminIntegrationIT {

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String USER_ID = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb";

    // Verifies create/update/reset/deactivate user actions persist in admin_users with role replacement semantics.
    @Test
    @Order(1)
    void shouldPersistUserLifecycleAndCredentials() {
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", USER_ID,
                "displayName", "Alice",
                "email", "alice@example.com",
                "roles", java.util.List.of("ADMIN"),
                "requestedBy", actor()),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> updated = adminApiClient().put(
            "/admin/users/" + USER_ID,
            Map.of(
                "displayName", "Alice Updated",
                "email", "alice.updated@example.com",
                "roles", java.util.List.of("VIEWER"),
                "requestedBy", actor()),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> reset = adminApiClient().post(
            "/admin/users/" + USER_ID + "/reset-credentials",
            Map.of("requestedBy", actor(), "reason", "rotation"),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(reset.getStatusCode().value()).isEqualTo(200);
        assertThat(reset.getBody().path("resetToken").asText()).isEqualTo(USER_ID + "-reset-1");

        final ResponseEntity<JsonNode> deactivated = adminApiClient().post(
            "/admin/users/" + USER_ID + "/deactivate",
            Map.of("requestedBy", actor(), "reason", "offboard"),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(deactivated.getStatusCode().value()).isEqualTo(200);
        assertThat(deactivated.getBody().path("active").asBoolean()).isFalse();

        final String roles = jdbcTemplate.queryForObject(
            "SELECT roles FROM admin_users WHERE user_id = ?",
            String.class,
            USER_ID);
        final Boolean active = jdbcTemplate.queryForObject(
            "SELECT active FROM admin_users WHERE user_id = ?",
            Boolean.class,
            USER_ID);
        final Integer resetCount = jdbcTemplate.queryForObject(
            "SELECT reset_count FROM admin_users WHERE user_id = ?",
            Integer.class,
            USER_ID);
        assertThat(roles).contains("VIEWER");
        assertThat(active).isFalse();
        assertThat(resetCount).isEqualTo(1);

        final ResponseEntity<JsonNode> duplicate = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", USER_ID,
                "displayName", "Alice",
                "email", "alice@example.com",
                "roles", java.util.List.of("ADMIN"),
                "requestedBy", actor()),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().path("code").asText()).isEqualTo("user_exists");
    }

    // Confirms user data survives a Spring context restart and reset token sequence continues.
    @Test
    @Order(2)
    void shouldRetainUserStateAcrossContextRestart() {
        final ResponseEntity<JsonNode> reset = adminApiClient().post(
            "/admin/users/" + USER_ID + "/reset-credentials",
            Map.of("requestedBy", actor(), "reason", "second rotation"),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);

        assertThat(reset.getStatusCode().value()).isEqualTo(200);
        assertThat(reset.getBody().path("resetToken").asText()).isEqualTo(USER_ID + "-reset-2");
    }

    // Ensures unknown user operations map to the API not-found contract.
    @Test
    @Order(3)
    void shouldReturnNotFoundForUnknownUser() {
        final String unknownUserId = UUID.randomUUID().toString();
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users/" + unknownUserId + "/deactivate",
            Map.of("requestedBy", actor(), "reason", "missing"),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().path("code").asText()).isEqualTo("user_not_found");
    }

    private Map<String, Object> actor() {
        return Map.of("id", OPERATOR_ID, "displayName", "User Admin");
    }
}
