package xyz.tcheeric.cashu.mint.rest.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.common.nut17.JsonRpcError;
import xyz.tcheeric.cashu.common.nut17.JsonRpcResponse;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.mint.rest.service.SubscriptionManager;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests Nut17WebSocketHandler message handling and lifecycle events.
 */
@ExtendWith(MockitoExtension.class)
class Nut17WebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private Nut17WebSocketHandler handler;

    @Mock
    private SubscriptionManager subscriptionManager;

    @Mock
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new Nut17WebSocketHandler(objectMapper, subscriptionManager);

        lenient().when(session.getId()).thenReturn("test-session");
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
    }

    // Tests that a valid subscribe request creates a subscription.
    @Test
    void handleTextMessage_Subscribe_Success() throws Exception {
        String requestJson = """
            {
                "jsonrpc": "2.0",
                "method": "subscribe",
                "params": {
                    "kind": "proof_state",
                    "filters": [{"ids": ["proof-1", "proof-2"]}]
                },
                "id": "req-123"
            }
            """;

        when(subscriptionManager.subscribe(eq(session), eq(SubscriptionKind.proof_state), anyList()))
                .thenReturn("sub-abc");

        handler.handleTextMessage(session, new TextMessage(requestJson));

        verify(subscriptionManager).subscribe(eq(session), eq(SubscriptionKind.proof_state), eq(List.of("proof-1", "proof-2")));

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());

        JsonRpcResponse response = objectMapper.readValue(responseCaptor.getValue().getPayload(), JsonRpcResponse.class);
        assertEquals("req-123", response.getId());
        assertNull(response.getError());
        assertNotNull(response.getResult());
    }

    // Tests that an unsubscribe request removes the subscription.
    @Test
    void handleTextMessage_Unsubscribe_Success() throws Exception {
        String requestJson = """
            {
                "jsonrpc": "2.0",
                "method": "unsubscribe",
                "params": {
                    "subId": "sub-to-remove"
                },
                "id": "req-456"
            }
            """;

        when(subscriptionManager.unsubscribe("test-session", "sub-to-remove")).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage(requestJson));

        verify(subscriptionManager).unsubscribe("test-session", "sub-to-remove");

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());

        JsonRpcResponse response = objectMapper.readValue(responseCaptor.getValue().getPayload(), JsonRpcResponse.class);
        assertEquals("req-456", response.getId());
        assertNull(response.getError());
    }

    // Tests that an unsubscribe for unknown subscription returns error.
    @Test
    void handleTextMessage_Unsubscribe_NotFound() throws Exception {
        String requestJson = """
            {
                "jsonrpc": "2.0",
                "method": "unsubscribe",
                "params": {
                    "subId": "unknown-sub"
                },
                "id": "req-789"
            }
            """;

        when(subscriptionManager.unsubscribe("test-session", "unknown-sub")).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage(requestJson));

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());

        JsonRpcResponse response = objectMapper.readValue(responseCaptor.getValue().getPayload(), JsonRpcResponse.class);
        assertEquals("req-789", response.getId());
        assertNotNull(response.getError());
        assertEquals(JsonRpcError.INVALID_PARAMS, response.getError().getCode());
    }

    // Tests that an unknown method returns error.
    @Test
    void handleTextMessage_UnknownMethod_ReturnsError() throws Exception {
        String requestJson = """
            {
                "jsonrpc": "2.0",
                "method": "unknown_method",
                "id": "req-xxx"
            }
            """;

        handler.handleTextMessage(session, new TextMessage(requestJson));

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());

        JsonRpcResponse response = objectMapper.readValue(responseCaptor.getValue().getPayload(), JsonRpcResponse.class);
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.getError().getCode());
    }

    // Tests that invalid JSON returns parse error.
    @Test
    void handleTextMessage_InvalidJson_ReturnsParseError() throws Exception {
        String invalidJson = "{ not valid json }";

        handler.handleTextMessage(session, new TextMessage(invalidJson));

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());

        JsonRpcResponse response = objectMapper.readValue(responseCaptor.getValue().getPayload(), JsonRpcResponse.class);
        assertEquals(JsonRpcError.PARSE_ERROR, response.getError().getCode());
    }

    // Tests that subscribe without params returns error.
    @Test
    void handleTextMessage_SubscribeMissingParams_ReturnsError() throws Exception {
        String requestJson = """
            {
                "jsonrpc": "2.0",
                "method": "subscribe",
                "id": "req-no-params"
            }
            """;

        handler.handleTextMessage(session, new TextMessage(requestJson));

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());

        JsonRpcResponse response = objectMapper.readValue(responseCaptor.getValue().getPayload(), JsonRpcResponse.class);
        assertEquals(JsonRpcError.INVALID_PARAMS, response.getError().getCode());
    }

    // Tests that connection close removes session from manager.
    @Test
    void afterConnectionClosed_RemovesSession() {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(subscriptionManager).removeSession("test-session");
    }

    // Tests that transport error removes session from manager.
    @Test
    void handleTransportError_RemovesSession() {
        handler.handleTransportError(session, new RuntimeException("Connection reset"));

        verify(subscriptionManager).removeSession("test-session");
    }
}
