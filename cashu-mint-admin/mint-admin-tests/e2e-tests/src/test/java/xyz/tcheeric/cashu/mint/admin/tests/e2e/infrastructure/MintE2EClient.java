package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * HTTP client for public cashu-mint REST endpoints.
 */
public final class MintE2EClient {

    private final JsonHttpClient httpClient;

    public MintE2EClient(final String baseUrl, final ObjectMapper objectMapper) {
        this.httpClient = new JsonHttpClient(baseUrl, objectMapper);
    }

    public ResponseEntity<JsonNode> get(final String path) {
        return httpClient.get(path, Map.of());
    }

    public ResponseEntity<JsonNode> post(final String path, final Object body) {
        return httpClient.post(path, body, Map.of());
    }
}
