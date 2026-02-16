package xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;

/**
 * Shared integration API client that injects admin auth and RBAC headers.
 */
public class AdminApiClient {

    private static final String TOKEN_HEADER = "X-Admin-Token";
    private static final String ROLES_HEADER = "X-Admin-Roles";

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public AdminApiClient(final String baseUrl, final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(final org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    public ResponseEntity<JsonNode> get(final String path, final String token, final String roles) {
        return exchange(path, HttpMethod.GET, null, token, roles);
    }

    public ResponseEntity<JsonNode> post(final String path, final Object body, final String token, final String roles) {
        return exchange(path, HttpMethod.POST, body, token, roles);
    }

    public ResponseEntity<JsonNode> put(final String path, final Object body, final String token, final String roles) {
        return exchange(path, HttpMethod.PUT, body, token, roles);
    }

    public ResponseEntity<JsonNode> exchange(final String path,
                                             final HttpMethod method,
                                             final Object body,
                                             final String token,
                                             final String roles) {
        try {
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            if (token != null) {
                headers.set(TOKEN_HEADER, token);
            }
            if (roles != null) {
                headers.set(ROLES_HEADER, roles);
            }

            final String payload = body == null ? null : objectMapper.writeValueAsString(body);
            final HttpEntity<String> requestEntity = new HttpEntity<>(payload, headers);
            final ResponseEntity<String> rawResponse =
                restTemplate.exchange(baseUrl + path, method, requestEntity, String.class);
            final JsonNode responseBody = parseBody(rawResponse.getBody());
            return new ResponseEntity<>(responseBody, rawResponse.getHeaders(), rawResponse.getStatusCode());
        } catch (final Exception ex) {
            throw new IllegalStateException("Integration API call failed for " + method + " " + path, ex);
        }
    }

    private JsonNode parseBody(final String body) throws Exception {
        if (body == null || body.isBlank()) {
            return NullNode.getInstance();
        }
        return objectMapper.readTree(body);
    }
}
