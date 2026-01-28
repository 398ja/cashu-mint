package xyz.tcheeric.cashu.mint.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.common.nut17.JsonRpcNotification;
import xyz.tcheeric.cashu.common.nut17.QuoteStatePayload;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Tests SubscriptionManager subscription lifecycle and notification delivery.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionManagerTest {

    private ObjectMapper objectMapper;
    private SubscriptionManager subscriptionManager;

    @Mock
    private WebSocketSession session1;

    @Mock
    private WebSocketSession session2;

    @Mock
    private ProofVaultService proofVaultService;

    @Mock
    private MintProtocolService mintProtocolService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        subscriptionManager = new SubscriptionManager(objectMapper, proofVaultService, mintProtocolService, "sat");

        lenient().when(session1.getId()).thenReturn("session-1");
        lenient().when(session2.getId()).thenReturn("session-2");
    }

    // Tests that subscribing creates a subscription with a unique ID.
    @Test
    void subscribe_CreatesSubscriptionWithUniqueId() {
        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-1"));

        assertNotNull(subId, "Subscription ID should be generated");
        assertEquals(1, subscriptionManager.getTotalSubscriptionCount());
        assertEquals(1, subscriptionManager.getActiveSessionCount());
    }

    // Tests that multiple subscriptions from same session are tracked.
    @Test
    void subscribe_MultipleFromSameSession() {
        String subId1 = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-1"));
        String subId2 = subscriptionManager.subscribe(session1, SubscriptionKind.bolt11_mint_quote, List.of("quote-1"));

        assertNotEquals(subId1, subId2, "Each subscription should have unique ID");
        assertEquals(2, subscriptionManager.getTotalSubscriptionCount());
        assertEquals(1, subscriptionManager.getActiveSessionCount());
    }

    // Tests that unsubscribe removes the subscription.
    @Test
    void unsubscribe_RemovesSubscription() {
        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-1"));
        assertEquals(1, subscriptionManager.getTotalSubscriptionCount());

        boolean removed = subscriptionManager.unsubscribe("session-1", subId);

        assertTrue(removed, "Should return true for successful unsubscribe");
        assertEquals(0, subscriptionManager.getTotalSubscriptionCount());
    }

    // Tests that unsubscribe with wrong session ID fails.
    @Test
    void unsubscribe_WrongSessionId_ReturnsFalse() {
        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-1"));

        boolean removed = subscriptionManager.unsubscribe("wrong-session", subId);

        assertFalse(removed, "Should return false for wrong session ID");
        assertEquals(1, subscriptionManager.getTotalSubscriptionCount());
    }

    // Tests that removeSession cleans up all subscriptions for that session.
    @Test
    void removeSession_CleansUpAllSubscriptions() {
        subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-1"));
        subscriptionManager.subscribe(session1, SubscriptionKind.bolt11_mint_quote, List.of("quote-1"));
        subscriptionManager.subscribe(session2, SubscriptionKind.proof_state, List.of("proof-2"));

        assertEquals(3, subscriptionManager.getTotalSubscriptionCount());
        assertEquals(2, subscriptionManager.getActiveSessionCount());

        subscriptionManager.removeSession("session-1");

        assertEquals(1, subscriptionManager.getTotalSubscriptionCount());
        assertEquals(1, subscriptionManager.getActiveSessionCount());
    }

    // Tests that proof state notifications are delivered to matching subscribers.
    @Test
    void publishProofState_DeliversToMatchingSubscribers() throws IOException {
        when(session1.isOpen()).thenReturn(true);
        subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-1"));

        subscriptionManager.publishProofState("proof-y-1", "SPENT", null);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(messageCaptor.capture());

        String json = messageCaptor.getValue().getPayload();
        JsonRpcNotification notification = objectMapper.readValue(json, JsonRpcNotification.class);
        assertEquals("notification", notification.getMethod());
        assertNotNull(notification.getParams().getSubId());
    }

    // Tests that proof state notifications are not delivered to non-matching subscribers.
    @Test
    void publishProofState_DoesNotDeliverToNonMatching() throws IOException {
        lenient().when(session1.isOpen()).thenReturn(true);
        subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-1"));

        subscriptionManager.publishProofState("proof-y-OTHER", "SPENT", null);

        verify(session1, never()).sendMessage(any());
    }

    // Tests that quote state notifications are delivered to matching subscribers.
    @Test
    void publishQuoteState_DeliversToMatchingSubscribers() throws IOException {
        when(session1.isOpen()).thenReturn(true);
        subscriptionManager.subscribe(session1, SubscriptionKind.bolt11_mint_quote, List.of("quote-123"));

        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("quote-123");
        payload.setState("PAID");
        payload.setPaid(true);

        subscriptionManager.publishQuoteState(SubscriptionKind.bolt11_mint_quote, "quote-123", payload);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(messageCaptor.capture());

        String json = messageCaptor.getValue().getPayload();
        assertTrue(json.contains("quote-123"));
        assertTrue(json.contains("PAID"));
    }

    // Tests that closed sessions don't receive notifications.
    @Test
    void publish_SkipsClosedSessions() throws IOException {
        when(session1.isOpen()).thenReturn(false);
        subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-1"));

        subscriptionManager.publishProofState("proof-y-1", "SPENT", null);

        verify(session1, never()).sendMessage(any());
    }

    // Tests multiple subscribers receive the same notification.
    @Test
    void publish_MultipleSubscribers_AllReceive() throws IOException {
        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-shared"));
        subscriptionManager.subscribe(session2, SubscriptionKind.proof_state, List.of("proof-y-shared"));

        subscriptionManager.publishProofState("proof-y-shared", "SPENT", null);

        verify(session1).sendMessage(any(TextMessage.class));
        verify(session2).sendMessage(any(TextMessage.class));
    }

    // Tests sendCurrentState sends UNSPENT when proof not found in vault.
    @Test
    void sendCurrentState_ProofState_UnspentWhenNotInVault() throws Exception {
        when(session1.isOpen()).thenReturn(true);
        when(proofVaultService.retrieveProof("proof-y-1")).thenReturn(null);

        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-1"));
        subscriptionManager.sendCurrentState(session1, subId, SubscriptionKind.proof_state);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(messageCaptor.capture());

        String json = messageCaptor.getValue().getPayload();
        assertTrue(json.contains("UNSPENT"), "Should report UNSPENT for unknown proof");
    }

    // Tests sendCurrentState sends SPENT when proof is in vault.
    @Test
    void sendCurrentState_ProofState_SpentWhenInVault() throws Exception {
        when(session1.isOpen()).thenReturn(true);
        xyz.tcheeric.cashu.vault.db.model.ProofEntity proofEntity = new xyz.tcheeric.cashu.vault.db.model.ProofEntity();
        proofEntity.setState(xyz.tcheeric.cashu.vault.db.model.ProofEntity.STATE_SPENT);
        when(proofVaultService.retrieveProof("proof-y-1")).thenReturn(proofEntity);

        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-1"));
        subscriptionManager.sendCurrentState(session1, subId, SubscriptionKind.proof_state);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(messageCaptor.capture());

        String json = messageCaptor.getValue().getPayload();
        assertTrue(json.contains("SPENT"), "Should report SPENT for known proof");
    }

    // Tests sendCurrentState sends PENDING when proof is pending in vault.
    @Test
    void sendCurrentState_ProofState_PendingWhenPendingInVault() throws Exception {
        when(session1.isOpen()).thenReturn(true);
        xyz.tcheeric.cashu.vault.db.model.ProofEntity proofEntity = new xyz.tcheeric.cashu.vault.db.model.ProofEntity();
        proofEntity.setState(xyz.tcheeric.cashu.vault.db.model.ProofEntity.STATE_PENDING);
        when(proofVaultService.retrieveProof("proof-y-1")).thenReturn(proofEntity);

        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of("proof-y-1"));
        subscriptionManager.sendCurrentState(session1, subId, SubscriptionKind.proof_state);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(messageCaptor.capture());

        String json = messageCaptor.getValue().getPayload();
        assertTrue(json.contains("PENDING"), "Should report PENDING for pending proof");
    }
}
