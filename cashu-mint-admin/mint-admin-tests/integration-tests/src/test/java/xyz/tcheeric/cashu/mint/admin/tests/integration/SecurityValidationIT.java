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
        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/lifecycle/mints",
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // Verifies RBAC across all endpoint families: an operator holding only the wrong
    // role is refused, even while asserting the required role in a request header.
    @ParameterizedTest
    @MethodSource("rbacCases")
    void shouldEnforceRbacPerEndpointFamily(final HttpMethod method,
                                            final String path,
                                            final Map<String, Object> body,
                                            final String wrongRole) {
        final String credential = provisionOperator(List.of(wrongRole));

        final ResponseEntity<JsonNode> response = adminApiClient().exchange(
            path,
            method,
            body,
            credential,
            ALL_ROLES_CLAIM);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // Every role a caller could try to claim, so the header is proven inert.
    private static final String ALL_ROLES_CLAIM = "MINT_ADMIN,USER_ADMIN,OPS_ADMIN";

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
            Arguments.of(HttpMethod.GET, "/admin/lifecycle/mints", null, USER_ADMIN_ROLE),
            Arguments.of(
                HttpMethod.POST,
                "/admin/operations/mints/" + mintId + "/maintenance/schedule",
                Map.of(
                    "reason", "rbac",
                    "durationMinutes", 10,
                    "requestedBy", actor),
                USER_ADMIN_ROLE));
    }

    // The core defect: an operator must not gain a role by asserting it in a request
    // header. Provisions an operator holding only USER_ADMIN, then calls an OPS_ADMIN
    // endpoint with that operator's own credential while claiming OPS_ADMIN in the header.
    @Test
    void shouldRefuseRoleAssertedInHeaderThatOperatorDoesNotHold() {
        final String credential = provisionOperator(List.of(USER_ADMIN_ROLE));

        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/operations/mints/" + UUID.randomUUID() + "/controls",
            credential,
            OPS_ADMIN_ROLE);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // An operator's own roles are honoured without any header being sent.
    @Test
    void shouldAuthoriseFromOperatorRolesWithoutRolesHeader() {
        final String credential = provisionOperator(List.of(OPS_ADMIN_ROLE));

        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/operations/mints/" + UUID.randomUUID() + "/controls",
            credential,
            null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // A revoked operator loses access on the very next request.
    @Test
    void shouldRefuseRevokedOperator() {
        final String userId = UUID.randomUUID().toString();
        final String credential = provisionOperator(userId, List.of(OPS_ADMIN_ROLE));

        adminApiClient().post(
            "/admin/users/" + userId + "/deactivate",
            Map.of(
                "requestedBy", Map.of("id", OPERATOR_ID, "displayName", "Operator"),
                "reason", "revoked for test"),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);

        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/operations/mints/" + UUID.randomUUID() + "/controls",
            credential,
            null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // Reading the audit trail requires a role rather than merely a valid credential.
    @Test
    void shouldRefuseAuditReadWithoutRequiredRole() {
        final String credential = provisionOperator(List.of(OPS_ADMIN_ROLE));

        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/audit/events", credential, null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    private String provisionOperator(final List<String> roles) {
        return provisionOperator(UUID.randomUUID().toString(), roles);
    }

    // Creates an operator with the given roles and returns its issued credential.
    private String provisionOperator(final String userId, final List<String> roles) {
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", userId,
                "displayName", "Operator " + userId.substring(0, 8),
                "email", userId.substring(0, 8) + "@example.com",
                "roles", roles,
                "requestedBy", Map.of("id", OPERATOR_ID, "displayName", "Operator")),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> reset = adminApiClient().post(
            "/admin/users/" + userId + "/reset-credentials",
            Map.of(
                "requestedBy", Map.of("id", OPERATOR_ID, "displayName", "Operator"),
                "reason", "issue initial credential"),
            ADMIN_TOKEN,
            USER_ADMIN_ROLE);
        assertThat(reset.getStatusCode().value()).isEqualTo(200);

        final String credential = reset.getBody().path("resetToken").asText();
        assertThat(credential).isNotBlank();
        return credential;
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
