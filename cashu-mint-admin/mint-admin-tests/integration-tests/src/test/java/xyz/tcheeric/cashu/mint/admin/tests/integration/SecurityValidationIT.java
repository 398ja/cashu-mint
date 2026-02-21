package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure.AbstractAdminIntegrationIT;

@Sql(scripts = "classpath:sql/truncate_admin_tables.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class SecurityValidationIT extends AbstractAdminIntegrationIT {

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";

    // Ensures requests without auth token are rejected with 401.
    @Test
    void shouldRejectMissingToken() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users",
            validCreateUserPayload(),
            null,
            USER_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // Ensures requests with invalid auth token are rejected with 401.
    @Test
    void shouldRejectInvalidToken() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users",
            validCreateUserPayload(),
            "invalid-token",
            USER_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // Confirms valid token and role pass authentication and continue into normal endpoint processing.
    @Test
    void shouldAllowValidTokenAndRoleThroughAuthLayer() {
        final String mintId = UUID.randomUUID().toString();
        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/health/mints/" + mintId,
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // Verifies RBAC role checks across all endpoint families.
    @ParameterizedTest
    @MethodSource("rbacCases")
    void shouldEnforceRbacPerEndpointFamily(final HttpMethod method,
                                            final String path,
                                            final Map<String, Object> body,
                                            final String wrongRole) {
        final ResponseEntity<JsonNode> response = adminApiClient().exchange(
            path,
            method,
            body,
            ADMIN_TOKEN,
            wrongRole);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // Ensures bean validation returns 400 for malformed user payloads.
    @Test
    void shouldReturnBadRequestForInvalidUserPayload() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", " ",
                "displayName", "Alice",
                "email", "alice@example.com",
                "roles", List.of(),
                "requestedBy", actor()),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // Ensures bean validation returns 400 for malformed lifecycle payloads.
    @Test
    void shouldReturnBadRequestForInvalidLifecyclePayload() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", UUID.randomUUID().toString(),
                "requestedBy", actor(),
                "configuration", Map.of("versionTag", "v1")),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private static Stream<Arguments> rbacCases() {
        final String mintId = UUID.randomUUID().toString();
        final Map<String, Object> actor = Map.of("id", OPERATOR_ID, "displayName", "Operator");
        return Stream.of(
            Arguments.of(
                HttpMethod.POST,
                "/admin/lifecycle/mints",
                Map.of(
                    "mintId", mintId,
                    "requestedBy", actor,
                    "metadata", Map.of("displayName", "Mint", "description", "desc", "tags", List.of("it")),
                    "configuration", Map.of("versionTag", "v1")),
                USER_ADMIN_ROLE),
            Arguments.of(HttpMethod.POST, "/admin/users", validCreateUserPayload(), MINT_ADMIN_ROLE),
            Arguments.of(
                HttpMethod.POST,
                "/admin/alerts",
                Map.of(
                    "alertId", "alert-rbac-1",
                    "mintId", "mint-1",
                    "severity", "WARNING",
                    "summary", "RBAC check",
                    "requestedBy", actor),
                OPS_ADMIN_ROLE),
            Arguments.of(HttpMethod.GET, "/admin/health/mints/" + mintId, null, USER_ADMIN_ROLE),
            Arguments.of(
                HttpMethod.POST,
                "/admin/operations/mints/" + mintId + "/maintenance/schedule",
                Map.of(
                    "reason", "rbac",
                    "durationMinutes", 10,
                    "requestedBy", actor),
                ALERTS_ADMIN_ROLE));
    }

    private static Map<String, Object> validCreateUserPayload() {
        return Map.of(
            "userId", UUID.randomUUID().toString(),
            "displayName", "Alice",
            "email", "alice@example.com",
            "roles", List.of("ADMIN"),
            "requestedBy", Map.of("id", OPERATOR_ID, "displayName", "Operator"));
    }

    private Map<String, Object> actor() {
        return Map.of("id", OPERATOR_ID, "displayName", "Operator");
    }
}
