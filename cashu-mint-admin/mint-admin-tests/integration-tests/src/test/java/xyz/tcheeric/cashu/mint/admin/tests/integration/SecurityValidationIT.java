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
            rootCredential(),
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

    // Ensures bean validation returns 400 for malformed user payloads. Authenticates
    // with an operator credential, not the bootstrap token: the token is inert once
    // any operator exists, so it answers 401 and never reaches validation.
    @Test
    void shouldReturnBadRequestForInvalidUserPayload() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", " ",
                "displayName", "Alice",
                "email", "alice@example.com",
                "roles", List.of()),
            rootCredential(),
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
                "configuration", Map.of("versionTag", "v1")),
            rootCredential(),
            MINT_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private static Stream<Arguments> rbacCases() {
        final String mintId = UUID.randomUUID().toString();
        return Stream.of(
            Arguments.of(
                HttpMethod.POST,
                "/admin/lifecycle/mints",
                Map.of(
                    "mintId", mintId,
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
                    "durationMinutes", 10),
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
                "reason", "revoked for test"),
            rootCredential(),
            null);

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

    // The Audit Trail names the operator the server authenticated. A body field
    // naming somebody else is inert: it neither changes the attribution nor
    // refuses the request.
    @Test
    void shouldAttributeAuditToAuthenticatedOperatorIgnoringRequestBody() {
        final String userId = UUID.randomUUID().toString();
        final String credential = provisionOperator(userId, List.of(MINT_ADMIN_ROLE));
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", mintId,
                // Someone else entirely — the server has no reason to read this.
                "requestedBy", Map.of("id", UUID.randomUUID().toString(), "displayName", "Somebody"),
                "metadata", Map.of("displayName", "Mint", "description", "desc", "tags", List.of("it")),
                "configuration", Map.of("versionTag", "v1")),
            credential,
            null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT last_actor FROM mints WHERE mint_id = ?", String.class, UUID.fromString(mintId)))
            .isEqualTo(userId);
    }

    // A role change takes effect on the operator's next request, with no restart.
    @Test
    void shouldApplyRoleChangesOnTheNextRequest() {
        final String userId = UUID.randomUUID().toString();
        final String credential = provisionOperator(userId, List.of(USER_ADMIN_ROLE));

        final String path = "/admin/operations/mints/" + UUID.randomUUID() + "/controls";
        assertThat(adminApiClient().get(path, credential, null).getStatusCode().value()).isEqualTo(403);

        adminApiClient().post(
            "/admin/users/" + userId + "/roles",
            Map.of(
                "roles", List.of(USER_ADMIN_ROLE, OPS_ADMIN_ROLE)),
            rootCredential(),
            null);

        assertThat(adminApiClient().get(path, credential, null).getStatusCode().value()).isEqualTo(200);
    }

    private String provisionOperator(final List<String> roles) {
        return provisionOperator(UUID.randomUUID().toString(), roles);
    }

    // Creates an operator with the given roles and returns its issued credential.
    // The bootstrap credential works only while the operator store is empty, so the
    // first operator is created with it and everything after uses that operator —
    // exactly the handover a real deployment performs.
    private String provisionOperator(final String userId, final List<String> roles) {
        final String root = rootCredential();
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", userId,
                "displayName", "Operator " + userId.substring(0, 8),
                "email", userId.substring(0, 8) + "@example.com",
                "roles", roles),
            root,
            null);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        final String credential = created.getBody().path("credential").asText();
        assertThat(credential).isNotBlank();
        return credential;
    }

    private static Map<String, Object> validCreateUserPayload() {
        return Map.of(
            "userId", UUID.randomUUID().toString(),
            "displayName", "Alice",
            "email", "alice@example.com",
            "roles", List.of("ADMIN"));
    }

}
