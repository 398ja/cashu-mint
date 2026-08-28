package xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Shared integration API client. It replays a NAP session cookie: the admin API
 * has no other way in.
 */
public class AdminApiClient {

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

    public ResponseEntity<JsonNode> get(final String path, final String sessionCookie) {
        return exchange(path, HttpMethod.GET, null, sessionCookie);
    }

    public ResponseEntity<JsonNode> post(final String path, final Object body, final String sessionCookie) {
        return exchange(path, HttpMethod.POST, body, sessionCookie);
    }

    public ResponseEntity<JsonNode> put(final String path, final Object body, final String sessionCookie) {
        return exchange(path, HttpMethod.PUT, body, sessionCookie);
    }

    public ResponseEntity<JsonNode> exchange(final String path,
                                             final HttpMethod method,
                                             final Object body,
                                             final String sessionCookie) {
        try {
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            if (sessionCookie != null) {
                headers.set(HttpHeaders.COOKIE, sessionCookie);
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
