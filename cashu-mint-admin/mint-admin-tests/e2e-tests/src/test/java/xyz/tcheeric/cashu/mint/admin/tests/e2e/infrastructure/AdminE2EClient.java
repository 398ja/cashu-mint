package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * HTTP client for mint-admin-rest endpoints. It replays a NAP session cookie:
 * the admin API has no other way in.
 */
public final class AdminE2EClient {

    private final JsonHttpClient httpClient;
    private final String sessionCookie;

    public AdminE2EClient(final String baseUrl, final String sessionCookie, final ObjectMapper objectMapper) {
        this.httpClient = new JsonHttpClient(baseUrl, objectMapper);
        this.sessionCookie = sessionCookie;
    }

    public ResponseEntity<JsonNode> get(final String path) {
        return httpClient.get(path, sessionHeaders());
    }

    public ResponseEntity<JsonNode> post(final String path, final Object body) {
        return httpClient.post(path, body, sessionHeaders());
    }

    public ResponseEntity<JsonNode> put(final String path, final Object body) {
        return httpClient.put(path, body, sessionHeaders());
    }

    private Map<String, String> sessionHeaders() {
        return Map.of(HttpHeaders.COOKIE, sessionCookie);
    }
}
