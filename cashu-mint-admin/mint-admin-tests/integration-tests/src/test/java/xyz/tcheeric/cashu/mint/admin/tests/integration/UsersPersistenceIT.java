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
import xyz.tcheeric.cashu.mint.admin.tests.nap.NapTestHandshake;

@Sql(scripts = "classpath:sql/truncate_admin_tables.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class UsersPersistenceIT extends AbstractAdminIntegrationIT {

    private static final String USER_ID = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb";

    // Verifies create/update/deactivate user actions persist in admin_users with role replacement semantics.
    @Test
    @Order(1)
    void shouldPersistUserLifecycle() {
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", USER_ID,
                "displayName", "Alice",
                "email", "alice@example.com",
                "roles", java.util.List.of("USER_ADMIN"),
                "npub", NapTestHandshake.npub(NapTestHandshake.randomPrivateKey())),
            superAdminSession());
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> updated = adminApiClient().put(
            "/admin/users/" + USER_ID,
            Map.of(
                "displayName", "Alice Updated",
                "email", "alice.updated@example.com",
                "roles", java.util.List.of("OPS_ADMIN")),
            superAdminSession());
        assertThat(updated.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> deactivated = adminApiClient().post(
            "/admin/users/" + USER_ID + "/deactivate",
            Map.of("reason", "offboard"),
            superAdminSession());
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
        assertThat(roles).contains("OPS_ADMIN");
        assertThat(active).isFalse();

        final ResponseEntity<JsonNode> duplicate = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", USER_ID,
                "displayName", "Alice",
                "email", "alice@example.com",
                "roles", java.util.List.of("USER_ADMIN"),
                "npub", NapTestHandshake.npub(NapTestHandshake.randomPrivateKey())),
            superAdminSession());
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().path("code").asText()).isEqualTo("user_exists");
    }

    // Confirms user data survives a Spring context restart.
    @Test
    @Order(2)
    void shouldRetainUserStateAcrossContextRestart() {
        final ResponseEntity<JsonNode> reloaded = adminApiClient().get(
            "/admin/users/" + USER_ID,
            superAdminSession());

        assertThat(reloaded.getStatusCode().value()).isEqualTo(200);
        assertThat(reloaded.getBody().path("roles").toString()).contains("OPS_ADMIN");
        assertThat(reloaded.getBody().path("active").asBoolean()).isFalse();
    }

    // Ensures unknown user operations map to the API not-found contract.
    @Test
    @Order(3)
    void shouldReturnNotFoundForUnknownUser() {
        final String unknownUserId = UUID.randomUUID().toString();
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users/" + unknownUserId + "/deactivate",
            Map.of("reason", "missing"),
            superAdminSession());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().path("code").asText()).isEqualTo("user_not_found");
    }

}
