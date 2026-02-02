package xyz.tcheeric.cashu.mint.rest.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import xyz.tcheeric.cashu.mint.proto.nut.NUT17;
import xyz.tcheeric.cashu.common.nut17.*;
import xyz.tcheeric.cashu.mint.rest.service.SubscriptionManager;

/**
 * WebSocket handler for NUT-17 subscriptions.
 *
 * <p>Handles JSON-RPC 2.0 messages for subscribe/unsubscribe operations
 * and routes notifications to subscribed clients.
 *
 * <p><b>Security:</b> Subscription limits are enforced to prevent resource
 * exhaustion attacks (per Oracle Secure Coding Guidelines DOS-1).
 */
@Slf4j
@Component
public final class Nut17WebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SubscriptionManager subscriptionManager;

    public Nut17WebSocketHandler(ObjectMapper objectMapper, SubscriptionManager subscriptionManager) {
        this.objectMapper = objectMapper;
        this.subscriptionManager = subscriptionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("nut17_ws connection_established session_id={} remote_addr={}",
                session.getId(), session.getRemoteAddress());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("nut17_ws connection_closed session_id={} status={}",
                session.getId(), status);
        subscriptionManager.removeSession(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("nut17_ws message_received session_id={} payload_size={}",
                session.getId(), payload.length());

        try {
            JsonRpcRequest request = objectMapper.readValue(payload, JsonRpcRequest.class);
            handleRequest(session, request);
        } catch (JsonProcessingException e) {
            log.warn("nut17_ws parse_error session_id={} error={}", session.getId(), e.getMessage());
            sendError(session, null, JsonRpcError.PARSE_ERROR, "Parse error: " + e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("nut17_ws transport_error session_id={} error={}",
                session.getId(), exception.getMessage(), exception);
        subscriptionManager.removeSession(session.getId());
    }

    /**
     * Routes a JSON-RPC request to the appropriate handler.
     */
    private void handleRequest(WebSocketSession session, JsonRpcRequest request) {
        if (request.getMethod() == null) {
            sendError(session, request.getId(), JsonRpcError.INVALID_REQUEST, "Missing method");
            return;
        }

        switch (request.getMethod()) {
            case NUT17.METHOD_SUBSCRIBE -> handleSubscribe(session, request);
            case NUT17.METHOD_UNSUBSCRIBE -> handleUnsubscribe(session, request);
            default -> sendError(session, request.getId(), JsonRpcError.METHOD_NOT_FOUND,
                    "Unknown method: " + request.getMethod());
        }
    }

    /**
     * Handles a subscribe request.
     *
     * <p><b>Security:</b> Subscription limits are enforced by SubscriptionManager
     * to prevent resource exhaustion attacks.
     */
    private void handleSubscribe(WebSocketSession session, JsonRpcRequest request) {
        SubscriptionParams params = request.getParams();
        if (params == null || !NUT17.validateSubscriptionParams(params)) {
            sendError(session, request.getId(), JsonRpcError.INVALID_PARAMS, "Invalid subscription parameters");
            return;
        }

        String subId;
        try {
            subId = subscriptionManager.subscribe(
                    session,
                    params.getKind(),
                    NUT17.extractIds(params.getFilters())
            );
        } catch (IllegalArgumentException e) {
            // Subscription limit exceeded
            log.warn("nut17_ws subscribe_limit_exceeded session_id={} error={}",
                    session.getId(), e.getMessage());
            sendError(session, request.getId(), JsonRpcError.INVALID_PARAMS, e.getMessage());
            return;
        }

        log.info("nut17_ws subscribe session_id={} sub_id={} kind={} filter_count={}",
                session.getId(), subId, params.getKind(), params.getFilters().size());

        sendResponse(session, NUT17.subscribeResponse(request.getId(), subId));

        // Send current state for subscribed items
        subscriptionManager.sendCurrentState(session, subId, params.getKind());
    }

    /**
     * Handles an unsubscribe request.
     */
    private void handleUnsubscribe(WebSocketSession session, JsonRpcRequest request) {
        SubscriptionParams params = request.getParams();
        if (params == null || params.getSubId() == null) {
            sendError(session, request.getId(), JsonRpcError.INVALID_PARAMS, "Missing subId");
            return;
        }

        boolean removed = subscriptionManager.unsubscribe(session.getId(), params.getSubId());
        if (removed) {
            log.info("nut17_ws unsubscribe session_id={} sub_id={}", session.getId(), params.getSubId());
            sendResponse(session, NUT17.unsubscribeResponse(request.getId(), params.getSubId()));
        } else {
            sendError(session, request.getId(), JsonRpcError.INVALID_PARAMS,
                    "Subscription not found: " + params.getSubId());
        }
    }

    /**
     * Sends a JSON-RPC response to a WebSocket session.
     */
    private void sendResponse(WebSocketSession session, JsonRpcResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("nut17_ws send_error session_id={} error={}", session.getId(), e.getMessage());
        }
    }

    /**
     * Sends a JSON-RPC error response.
     */
    private void sendError(WebSocketSession session, String requestId, int code, String message) {
        sendResponse(session, NUT17.errorResponse(requestId, code, message));
    }
}
