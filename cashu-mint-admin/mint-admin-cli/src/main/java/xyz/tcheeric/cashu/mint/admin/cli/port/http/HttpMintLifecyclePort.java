package xyz.tcheeric.cashu.mint.admin.cli.port.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;

/**
 * Calls the mint-admin-rest lifecycle endpoints via HTTP.
 */
public class HttpMintLifecyclePort implements MintLifecyclePort {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpMintLifecyclePort(final String baseUrl,
                                  final String apiKey,
                                  final ObjectMapper objectMapper) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public LifecycleSummary execute(final MintLifecycleCommand command) {
        Objects.requireNonNull(command, "command");
        final MintLifecycleRequest request = command.request();
        return switch (command.operation()) {
            case CREATE -> doCreate(request);
            case UPDATE -> doUpdate(request);
            case PAUSE -> doTransition(request, "pause", LifecycleAction.PAUSE);
            case RESUME -> doTransition(request, "resume", LifecycleAction.RESUME);
            case RETIRE -> doTransition(request, "retire", LifecycleAction.RETIRE);
        };
    }

    private LifecycleSummary doCreate(final MintLifecycleRequest request) {
        final Map<String, Object> body = Map.of(
            "mintId", request.mintId(),
            "requestedBy", Map.of("id", request.operatorId(), "displayName", request.operatorId()),
            "metadata", Map.of("displayName", request.mintId()),
            "configuration", Map.of("versionTag", request.versionTag())
        );
        return sendPost(baseUrl + "/admin/lifecycle/mints", body, LifecycleAction.CREATE);
    }

    private LifecycleSummary doUpdate(final MintLifecycleRequest request) {
        final Map<String, Object> body = Map.of(
            "requestedBy", Map.of("id", request.operatorId(), "displayName", request.operatorId()),
            "metadata", Map.of("displayName", request.mintId()),
            "configuration", Map.of("versionTag", request.versionTag()),
            "revisionId", request.versionTag()
        );
        return sendPut(baseUrl + "/admin/lifecycle/mints/" + request.mintId(), body, LifecycleAction.UPDATE);
    }

    private LifecycleSummary doTransition(final MintLifecycleRequest request,
                                           final String action,
                                           final LifecycleAction lifecycleAction) {
        final Map<String, Object> body = Map.of(
            "requestedBy", Map.of("id", request.operatorId(), "displayName", request.operatorId()),
            "reason", action + " requested via CLI",
            "correlationId", request.correlationId() != null ? request.correlationId() : ""
        );
        return sendPost(baseUrl + "/admin/lifecycle/mints/" + request.mintId() + "/" + action,
            body, lifecycleAction);
    }

    private LifecycleSummary sendPost(final String url, final Map<String, Object> body,
                                       final LifecycleAction action) {
        return sendRequest(buildRequest(url, "POST", body), action);
    }

    private LifecycleSummary sendPut(final String url, final Map<String, Object> body,
                                      final LifecycleAction action) {
        return sendRequest(buildRequest(url, "PUT", body), action);
    }

    private HttpRequest buildRequest(final String url, final String method, final Map<String, Object> body) {
        try {
            final String json = objectMapper.writeValueAsString(body);
            return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Admin-Token", apiKey)
                .header("X-Admin-Roles", "MINT_ADMIN")
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    private LifecycleSummary sendRequest(final HttpRequest httpRequest, final LifecycleAction action) {
        try {
            final HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return parseResponse(response.body(), action);
        } catch (final IOException e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted", e);
        }
    }

    private LifecycleSummary parseResponse(final String responseBody, final LifecycleAction action) {
        try {
            final var tree = objectMapper.readTree(responseBody);
            return new LifecycleSummary(
                action,
                tree.path("mintId").asText(),
                tree.path("previousState").asText(null),
                tree.path("currentState").asText(),
                tree.path("versionTag").asText(),
                tree.path("changed").asBoolean(true),
                tree.path("message").asText("")
            );
        } catch (final IOException e) {
            throw new RuntimeException("Failed to parse response: " + e.getMessage(), e);
        }
    }
}
