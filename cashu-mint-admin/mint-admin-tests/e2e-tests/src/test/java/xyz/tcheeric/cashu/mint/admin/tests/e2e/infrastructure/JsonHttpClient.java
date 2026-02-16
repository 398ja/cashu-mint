package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Small HTTP JSON client used by E2E tests.
 */
public final class JsonHttpClient {

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public JsonHttpClient(final String baseUrl, final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(final org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    public ResponseEntity<JsonNode> get(final String path, final Map<String, String> headers) {
        return exchange(path, HttpMethod.GET, null, headers);
    }

    public ResponseEntity<JsonNode> post(final String path, final Object body, final Map<String, String> headers) {
        return exchange(path, HttpMethod.POST, body, headers);
    }

    public ResponseEntity<JsonNode> put(final String path, final Object body, final Map<String, String> headers) {
        return exchange(path, HttpMethod.PUT, body, headers);
    }

    private ResponseEntity<JsonNode> exchange(final String path,
                                              final HttpMethod method,
                                              final Object body,
                                              final Map<String, String> headers) {
        try {
            final HttpHeaders requestHeaders = new HttpHeaders();
            requestHeaders.setContentType(MediaType.APPLICATION_JSON);
            requestHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.forEach(requestHeaders::set);

            final String payload = body == null ? null : objectMapper.writeValueAsString(body);
            final HttpEntity<String> requestEntity = new HttpEntity<>(payload, requestHeaders);
            final ResponseEntity<String> rawResponse =
                restTemplate.exchange(baseUrl + path, method, requestEntity, String.class);
            final JsonNode responseBody = parseBody(rawResponse.getBody());
            return new ResponseEntity<>(responseBody, rawResponse.getHeaders(), rawResponse.getStatusCode());
        } catch (final Exception ex) {
            throw new IllegalStateException("E2E API call failed for " + method + " " + path, ex);
        }
    }

    private JsonNode parseBody(final String body) throws Exception {
        if (body == null || body.isBlank()) {
            return NullNode.getInstance();
        }
        return objectMapper.readTree(body);
    }
}
