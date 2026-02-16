package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * HTTP client for mint-admin-rest endpoints with admin authentication headers.
 */
public final class AdminE2EClient {

    private static final String TOKEN_HEADER = "X-Admin-Token";
    private static final String ROLES_HEADER = "X-Admin-Roles";

    private final JsonHttpClient httpClient;
    private final String token;

    public AdminE2EClient(final String baseUrl, final String token, final ObjectMapper objectMapper) {
        this.httpClient = new JsonHttpClient(baseUrl, objectMapper);
        this.token = token;
    }

    public ResponseEntity<JsonNode> get(final String path, final String roles) {
        return httpClient.get(path, authHeaders(roles));
    }

    public ResponseEntity<JsonNode> post(final String path, final Object body, final String roles) {
        return httpClient.post(path, body, authHeaders(roles));
    }

    public ResponseEntity<JsonNode> put(final String path, final Object body, final String roles) {
        return httpClient.put(path, body, authHeaders(roles));
    }

    private Map<String, String> authHeaders(final String roles) {
        return Map.of(
            TOKEN_HEADER, token,
            ROLES_HEADER, roles
        );
    }
}
