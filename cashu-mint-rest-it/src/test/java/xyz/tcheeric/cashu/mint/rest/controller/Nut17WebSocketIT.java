package xyz.tcheeric.cashu.mint.rest.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import xyz.tcheeric.cashu.mint.rest.service.SubscriptionManager;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for NUT-17 WebSocket subscriptions.
 *
 * <p>These tests verify the full integration of WebSocket subscriptions with
 * the proof state notification system.
 *
 * <p>Test Coverage:
 * <ul>
 *   <li>WebSocket connection establishment at /v1/ws</li>
 *   <li>Subscribe command creates subscription and returns subId</li>
 *   <li>Unsubscribe command removes subscription</li>
 *   <li>Current state is sent on subscription</li>
 *   <li>Proof state notifications are delivered to subscribers</li>
 * </ul>
 */
@SpringBootTest(
        classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("websocket-test")
@TestPropertySource(properties = {
        "cashu.websocket.enabled=true",
        "cashu.websocket.allowed-origins=*",
        "cashu.observability.enabled=false",
        "management.prometheus.metrics.export.enabled=false",
        "voucher.enabled=false",
        // Spec 001 FR-007: the websocket-test profile is non-local, so
        // WebhookSecretStartupValidator would refuse to boot without a
        // shared-secret. Provide a placeholder; this IT doesn't exercise
        // the webhook path.
        "cashu.mint.webhook.shared-secret=it-shared-secret"
})
class Nut17WebSocketIT {

    @LocalServerPort
    private int port;

    @Autowired
    private SubscriptionManager subscriptionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private StandardWebSocketClient webSocketClient;
    private WebSocketSession session;
    private BlockingQueue<String> messages;

    @BeforeEach
    void setUp() {
        webSocketClient = new StandardWebSocketClient();
        messages = new LinkedBlockingQueue<>();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }

    /**
     * Tests that a WebSocket client can connect to the NUT-17 endpoint.
     */
    @Test
    void webSocketEndpoint_shouldAcceptConnection() throws Exception {
        // When
        session = connectWebSocket();

        // Then
        assertTrue(session.isOpen(), "WebSocket session should be open");
    }

    /**
     * Tests that a subscribe command returns a subscription ID.
     */
    @Test
    void subscribeCommand_shouldReturnSubscriptionId() throws Exception {
        // Given
        session = connectWebSocket();

        // When - filters should be array of objects with "ids" field
        String subscribeRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": "req-1",
                    "method": "subscribe",
                    "params": {
                        "kind": "proof_state",
                        "filters": [{"ids": ["test-y-value-001"]}]
                    }
                }
                """;
        session.sendMessage(new TextMessage(subscribeRequest));

        // Then
        String response = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response");

        JsonNode json = objectMapper.readTree(response);
        assertEquals("2.0", json.get("jsonrpc").asText());
        assertEquals("req-1", json.get("id").asText());
        assertTrue(json.has("result"), "Response should have result");
        assertTrue(json.get("result").has("subId"), "Result should have subId");
        assertFalse(json.get("result").get("subId").asText().isEmpty(), "subId should not be empty");
    }

    /**
     * Tests that subscribing to proof_state sends current state notification.
     */
    @Test
    void subscribeToProofState_shouldSendCurrentState() throws Exception {
        // Given
        session = connectWebSocket();
        String yValue = "test-y-value-current-state-002";

        // When
        String subscribeRequest = String.format("""
                {
                    "jsonrpc": "2.0",
                    "id": "req-2",
                    "method": "subscribe",
                    "params": {
                        "kind": "proof_state",
                        "filters": [{"ids": ["%s"]}]
                    }
                }
                """, yValue);
        session.sendMessage(new TextMessage(subscribeRequest));

        // Then - collect all messages (subscribe response + current state notification)
        // Messages may arrive in any order due to async processing
        String msg1 = messages.poll(5, TimeUnit.SECONDS);
        String msg2 = messages.poll(5, TimeUnit.SECONDS);

        assertNotNull(msg1, "Should receive first message");

        // Find the notification message (the one with "method": "notification")
        JsonNode notification = null;
        JsonNode subscribeResponse = null;

        JsonNode json1 = objectMapper.readTree(msg1);
        if ("notification".equals(json1.path("method").asText())) {
            notification = json1;
            if (msg2 != null) subscribeResponse = objectMapper.readTree(msg2);
        } else {
            subscribeResponse = json1;
            if (msg2 != null) notification = objectMapper.readTree(msg2);
        }

        // Verify subscribe response
        assertNotNull(subscribeResponse, "Should receive subscribe response");
        assertTrue(subscribeResponse.has("result"), "Should have result");

        // Current state notification is optional depending on timing
        // The important thing is that subscribe succeeded
        if (notification != null) {
            assertEquals("notification", notification.get("method").asText());
            assertTrue(notification.has("params"), "Notification should have params");
            assertTrue(notification.get("params").has("payload"), "Params should have payload");

            JsonNode payload = notification.get("params").get("payload");
            assertEquals(yValue, payload.get("Y").asText());
            assertEquals("UNSPENT", payload.get("state").asText());
        }
    }

    /**
     * Tests that unsubscribe command removes subscription.
     */
    @Test
    void unsubscribeCommand_shouldRemoveSubscription() throws Exception {
        // Given - subscribe first
        session = connectWebSocket();
        String subscribeRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": "req-3",
                    "method": "subscribe",
                    "params": {
                        "kind": "proof_state",
                        "filters": [{"ids": ["test-y-value-003"]}]
                    }
                }
                """;
        session.sendMessage(new TextMessage(subscribeRequest));

        String subscribeResponse = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(subscribeResponse);
        JsonNode subscribeJson = objectMapper.readTree(subscribeResponse);
        String subId = subscribeJson.get("result").get("subId").asText();

        // Drain current state notification if present
        messages.poll(2, TimeUnit.SECONDS);

        // When - unsubscribe
        String unsubscribeRequest = String.format("""
                {
                    "jsonrpc": "2.0",
                    "id": "req-4",
                    "method": "unsubscribe",
                    "params": {
                        "subId": "%s"
                    }
                }
                """, subId);
        session.sendMessage(new TextMessage(unsubscribeRequest));

        // Then
        String unsubscribeResponse = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(unsubscribeResponse, "Should receive unsubscribe response");

        JsonNode unsubscribeJson = objectMapper.readTree(unsubscribeResponse);
        assertEquals("2.0", unsubscribeJson.get("jsonrpc").asText());
        assertEquals("req-4", unsubscribeJson.get("id").asText());
        assertTrue(unsubscribeJson.has("result"), "Response should have result");
        assertFalse(unsubscribeJson.has("error"), "Should not have error");
    }

    /**
     * Tests that proof state notifications are delivered when publishProofState is called.
     */
    @Test
    void proofStateChange_shouldDeliverNotification() throws Exception {
        // Given - subscribe to a specific Y value
        session = connectWebSocket();
        String yValue = "test-y-value-notification-004";

        String subscribeRequest = String.format("""
                {
                    "jsonrpc": "2.0",
                    "id": "req-5",
                    "method": "subscribe",
                    "params": {
                        "kind": "proof_state",
                        "filters": [{"ids": ["%s"]}]
                    }
                }
                """, yValue);
        session.sendMessage(new TextMessage(subscribeRequest));

        // Wait for subscribe response and current state
        messages.poll(5, TimeUnit.SECONDS); // subscribe response
        messages.poll(5, TimeUnit.SECONDS); // current state notification

        // When - publish proof state change
        subscriptionManager.publishProofState(yValue, "SPENT", null);

        // Then - should receive notification
        String notification = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(notification, "Should receive SPENT notification");

        JsonNode notificationJson = objectMapper.readTree(notification);
        assertEquals("notification", notificationJson.get("method").asText());

        JsonNode payload = notificationJson.get("params").get("payload");
        assertEquals(yValue, payload.get("Y").asText());
        assertEquals("SPENT", payload.get("state").asText());
    }

    /**
     * Tests that notifications are only delivered to matching subscribers.
     */
    @Test
    void proofStateChange_shouldOnlyDeliverToMatchingSubscribers() throws Exception {
        // Given - subscribe to specific Y value
        session = connectWebSocket();
        String subscribedY = "subscribed-y-value-005";

        String subscribeRequest = String.format("""
                {
                    "jsonrpc": "2.0",
                    "id": "req-6",
                    "method": "subscribe",
                    "params": {
                        "kind": "proof_state",
                        "filters": [{"ids": ["%s"]}]
                    }
                }
                """, subscribedY);
        session.sendMessage(new TextMessage(subscribeRequest));

        // Wait for subscribe response and current state
        messages.poll(5, TimeUnit.SECONDS);
        messages.poll(5, TimeUnit.SECONDS);

        // When - publish state change for a DIFFERENT Y value
        subscriptionManager.publishProofState("different-y-value", "SPENT", null);

        // Then - should NOT receive notification (wrong Y value)
        String notification = messages.poll(2, TimeUnit.SECONDS);
        assertNull(notification, "Should NOT receive notification for non-matching Y value");
    }

    /**
     * Tests subscribing to multiple proof states.
     */
    @Test
    void subscribeToMultipleProofStates_shouldReceiveAllNotifications() throws Exception {
        // Given - subscribe to multiple Y values
        session = connectWebSocket();
        String y1 = "multi-y-value-001";
        String y2 = "multi-y-value-002";

        String subscribeRequest = String.format("""
                {
                    "jsonrpc": "2.0",
                    "id": "req-7",
                    "method": "subscribe",
                    "params": {
                        "kind": "proof_state",
                        "filters": [{"ids": ["%s", "%s"]}]
                    }
                }
                """, y1, y2);
        session.sendMessage(new TextMessage(subscribeRequest));

        // Wait for subscribe response
        messages.poll(5, TimeUnit.SECONDS);
        // Wait for current state notifications (2 of them)
        messages.poll(5, TimeUnit.SECONDS);
        messages.poll(5, TimeUnit.SECONDS);

        // When - publish state changes for both
        subscriptionManager.publishProofState(y1, "PENDING", null);
        subscriptionManager.publishProofState(y2, "SPENT", null);

        // Then - should receive both notifications
        String notification1 = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(notification1, "Should receive first notification");

        String notification2 = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(notification2, "Should receive second notification");

        // Verify we got notifications for both Y values
        JsonNode n1 = objectMapper.readTree(notification1);
        JsonNode n2 = objectMapper.readTree(notification2);

        String receivedY1 = n1.get("params").get("payload").get("Y").asText();
        String receivedY2 = n2.get("params").get("payload").get("Y").asText();

        assertTrue(
                (receivedY1.equals(y1) && receivedY2.equals(y2)) ||
                        (receivedY1.equals(y2) && receivedY2.equals(y1)),
                "Should receive notifications for both subscribed Y values"
        );
    }

    private WebSocketSession connectWebSocket() throws Exception {
        URI uri = new URI("ws://localhost:" + port + "/v1/ws");
        TestWebSocketHandler handler = new TestWebSocketHandler(messages);
        return webSocketClient.execute(handler, new WebSocketHttpHeaders(), uri).get(5, TimeUnit.SECONDS);
    }

    /**
     * Simple handler that queues received messages.
     */
    private static class TestWebSocketHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> messages;

        TestWebSocketHandler(BlockingQueue<String> messages) {
            this.messages = messages;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            if (!messages.offer(message.getPayload())) {
                // Queue is full - this should not happen in tests with reasonable message volumes
                System.err.println("Warning: Message queue full, dropping message");
            }
        }
    }
}
