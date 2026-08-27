package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import xyz.tcheeric.cashu.mint.admin.tests.nap.NapTestHandshake;

@Sql(scripts = "classpath:sql/truncate_admin_tables.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class SecurityValidationIT extends AbstractAdminIntegrationIT {

    // Ensures requests without a session are rejected with 401.
    @Test
    void shouldRejectMissingSession() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users",
            validCreateUserPayload(),
            null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // Ensures a session cookie the server never issued is rejected with 401.
    @Test
    void shouldRejectUnknownSession() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/users",
            validCreateUserPayload(),
            "cashu_admin_session=" + UUID.randomUUID());
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // An npub with no Operator profile proves a key, not an entitlement: no session is issued.
    @Test
    void shouldRefuseHandshakeForUnknownNpub() {
        assertThatThrownBy(() -> NapTestHandshake.sessionCookie(baseUrl(), NapTestHandshake.randomPrivateKey()))
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
    }

    // Confirms a valid session passes authentication and continues into normal endpoint processing.
    @Test
    void shouldAllowSessionThroughAuthLayer() {
        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/lifecycle/mints",
            superAdminSession());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // Verifies permissions across all endpoint families: an operator holding only the
    // wrong role is refused, because the session carries the roles, not the request.
    @ParameterizedTest
    @MethodSource("rbacCases")
    void shouldEnforceRbacPerEndpointFamily(final HttpMethod method,
                                            final String path,
                                            final Map<String, Object> body,
                                            final String wrongRole) {
        final String session = operatorSession(UUID.randomUUID().toString(), List.of(wrongRole));

        final ResponseEntity<JsonNode> response = adminApiClient().exchange(path, method, body, session);
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
                "roles", List.of()),
            superAdminSession());
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
            superAdminSession());
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

    // An operator's own roles are honoured, with nothing but the session cookie sent.
    @Test
    void shouldAuthoriseFromOperatorRoles() {
        final String session = operatorSession(UUID.randomUUID().toString(), List.of(OPS_ADMIN_ROLE));

        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/operations/mints/" + UUID.randomUUID() + "/controls",
            session);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // Reading the audit trail requires a permission rather than merely a valid session.
    @Test
    void shouldRefuseAuditReadWithoutRequiredRole() {
        final String session = operatorSession(UUID.randomUUID().toString(), List.of(OPS_ADMIN_ROLE));

        final ResponseEntity<JsonNode> response = adminApiClient().get("/admin/audit/events", session);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // The Audit Trail names the operator the server authenticated. A body field
    // naming somebody else is inert: it neither changes the attribution nor
    // refuses the request.
    @Test
    void shouldAttributeAuditToAuthenticatedOperatorIgnoringRequestBody() {
        final String userId = UUID.randomUUID().toString();
        final String session = operatorSession(userId, List.of(MINT_ADMIN_ROLE));
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", mintId,
                // Someone else entirely — the server has no reason to read this.
                "requestedBy", Map.of("id", UUID.randomUUID().toString(), "displayName", "Somebody"),
                "metadata", Map.of("displayName", "Mint", "description", "desc", "tags", List.of("it")),
                "configuration", Map.of("versionTag", "v1")),
            session);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT last_actor FROM mints WHERE mint_id = ?", String.class, UUID.fromString(mintId)))
            .isEqualTo(userId);
    }

    // A revoked operator loses access on the very next request, session or no session.
    @Test
    void shouldRefuseRevokedOperator() {
        final String userId = UUID.randomUUID().toString();
        final String session = operatorSession(userId, List.of(OPS_ADMIN_ROLE));

        adminApiClient().post(
            "/admin/users/" + userId + "/deactivate",
            Map.of("reason", "revoked for test"),
            superAdminSession());

        final ResponseEntity<JsonNode> response = adminApiClient().get(
            "/admin/operations/mints/" + UUID.randomUUID() + "/controls",
            session);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // A role change takes effect on the operator's next request, with no re-login.
    @Test
    void shouldApplyRoleChangesOnTheNextRequest() {
        final String userId = UUID.randomUUID().toString();
        final String session = operatorSession(userId, List.of(USER_ADMIN_ROLE));

        final String path = "/admin/operations/mints/" + UUID.randomUUID() + "/controls";
        assertThat(adminApiClient().get(path, session).getStatusCode().value()).isEqualTo(403);

        adminApiClient().post(
            "/admin/users/" + userId + "/roles",
            Map.of("roles", List.of(USER_ADMIN_ROLE, OPS_ADMIN_ROLE)),
            superAdminSession());

        assertThat(adminApiClient().get(path, session).getStatusCode().value()).isEqualTo(200);
    }

    private static Map<String, Object> validCreateUserPayload() {
        return Map.of(
            "userId", UUID.randomUUID().toString(),
            "displayName", "Alice",
            "email", "alice@example.com",
            "roles", List.of("USER_ADMIN"),
            "npub", NapTestHandshake.npub(NapTestHandshake.randomPrivateKey()));
    }

}
