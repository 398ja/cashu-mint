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
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Tests SubscriptionManager subscription lifecycle and notification delivery.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionManagerTest {

    /** A real compressed point: the current-state lookup refuses anything that is not one (#487). */
    private static final String REAL_Y =
            "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";

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

    // #511: hex is case-insensitive and the mint publishes lowercase Ys, so a wallet that
    // subscribed with an uppercase Y must still receive the update, echoed in its own spelling.
    @Test
    void publishProofState_ReachesASubscriberWhoWroteTheYInUppercase() throws IOException {
        when(session1.isOpen()).thenReturn(true);
        String uppercaseY = REAL_Y.toUpperCase(Locale.ROOT);
        subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of(uppercaseY));

        subscriptionManager.publishProofState(REAL_Y, "SPENT", null);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getPayload().contains(uppercaseY),
                "The notification should carry the Y as the subscriber wrote it");
    }

    // #511: unsubscribing an uppercase subscription empties the case-insensitive index entry it was
    // filed under. Checked on the index itself: a publish would skip the removed subscription
    // anyway, so it cannot show whether the entry was left behind.
    @Test
    void unsubscribe_RemovesAnUppercaseSubscriptionFromTheIndex() {
        String subId = subscriptionManager.subscribe(
                session1, SubscriptionKind.proof_state, List.of(REAL_Y.toUpperCase(Locale.ROOT)));
        assertEquals(1, subscriptionManager.indexedSubscriberCount(SubscriptionKind.proof_state, REAL_Y));

        subscriptionManager.unsubscribe(session1.getId(), subId);

        assertEquals(0, subscriptionManager.indexedSubscriberCount(SubscriptionKind.proof_state, REAL_Y));
    }

    // #511: closing the session likewise empties the index entry of an uppercase subscription.
    @Test
    void removeSession_RemovesAnUppercaseSubscriptionFromTheIndex() {
        subscriptionManager.subscribe(
                session1, SubscriptionKind.proof_state, List.of(REAL_Y.toUpperCase(Locale.ROOT)));

        subscriptionManager.removeSession(session1.getId());

        assertEquals(0, subscriptionManager.indexedSubscriberCount(SubscriptionKind.proof_state, REAL_Y));
    }

    // One subscription naming the same Y in two cases is one proof: it gets one initial state and
    // one update, echoed in the spelling it listed first.
    @Test
    void subscribe_TheSameYInTwoCasesIsOneTarget() throws Exception {
        when(session1.isOpen()).thenReturn(true);
        when(proofVaultService.retrieveProof(StorageKey.of(REAL_Y))).thenReturn(null);
        String uppercaseY = REAL_Y.toUpperCase(Locale.ROOT);
        String subId = subscriptionManager.subscribe(
                session1, SubscriptionKind.proof_state, List.of(uppercaseY, REAL_Y));

        subscriptionManager.sendCurrentState(session1, subId, SubscriptionKind.proof_state);
        subscriptionManager.publishProofState(REAL_Y, "SPENT", null);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1, times(2)).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getAllValues().stream().allMatch(m -> m.getPayload().contains(uppercaseY)),
                "Both notifications should use the first spelling the subscriber listed");
    }

    // Two subscribers who wrote the same Y differently each get it back in their own spelling.
    @Test
    void publishProofState_EchoesEachSubscribersOwnSpelling() throws IOException {
        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);
        String uppercaseY = REAL_Y.toUpperCase(Locale.ROOT);
        subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of(uppercaseY));
        subscriptionManager.subscribe(session2, SubscriptionKind.proof_state, List.of(REAL_Y));

        subscriptionManager.publishProofState(REAL_Y, "SPENT", null);

        ArgumentCaptor<TextMessage> toFirst = ArgumentCaptor.forClass(TextMessage.class);
        ArgumentCaptor<TextMessage> toSecond = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(toFirst.capture());
        verify(session2).sendMessage(toSecond.capture());
        assertTrue(toFirst.getValue().getPayload().contains(uppercaseY));
        assertTrue(toSecond.getValue().getPayload().contains(REAL_Y));
    }

    // Quote ids are opaque, not hex: they stay case-sensitive, so a differently cased id is a
    // different quote and gets no notification.
    @Test
    void publishQuoteState_StaysCaseSensitive() throws IOException {
        lenient().when(session1.isOpen()).thenReturn(true);
        subscriptionManager.subscribe(session1, SubscriptionKind.bolt11_melt_quote, List.of("Quote-A"));

        subscriptionManager.publishQuoteState(SubscriptionKind.bolt11_melt_quote, "quote-a",
                new QuoteStatePayload());

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
        when(proofVaultService.retrieveProof(StorageKey.of(REAL_Y))).thenReturn(null);

        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of(REAL_Y));
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
        when(proofVaultService.retrieveProof(StorageKey.of(REAL_Y))).thenReturn(proofEntity);

        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of(REAL_Y));
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
        when(proofVaultService.retrieveProof(StorageKey.of(REAL_Y))).thenReturn(proofEntity);

        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, List.of(REAL_Y));
        subscriptionManager.sendCurrentState(session1, subId, SubscriptionKind.proof_state);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(messageCaptor.capture());

        String json = messageCaptor.getValue().getPayload();
        assertTrue(json.contains("PENDING"), "Should report PENDING for pending proof");
    }

    // A subscribed id that is not a curve point cannot name a proof. It gets no state at all rather
    // than UNSPENT, which would falsely tell the subscriber the proof is spendable (#487). A valid id
    // in the same subscription still gets its state, so the one message sent proves the session was
    // live and the malformed id was skipped, not silenced.
    @Test
    void sendCurrentState_ProofState_MalformedYGetsNoState() throws Exception {
        when(session1.isOpen()).thenReturn(true);
        when(proofVaultService.retrieveProof(StorageKey.of(REAL_Y))).thenReturn(null);
        String subId = subscriptionManager.subscribe(
                session1, SubscriptionKind.proof_state, List.of("proof-y-1", REAL_Y));
        subscriptionManager.sendCurrentState(session1, subId, SubscriptionKind.proof_state);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1, times(1)).sendMessage(messageCaptor.capture());
        assertFalse(messageCaptor.getValue().getPayload().contains("proof-y-1"),
                "The malformed id must not be reported, least of all as UNSPENT");
        verify(proofVaultService, times(1)).retrieveProof(any(StorageKey.class));
    }

    // A null id is client input too. It gets no state, and must not escape as an exception that
    // would close the subscriber's session and drop its other subscriptions with it.
    @Test
    void sendCurrentState_ProofState_NullYGetsNoStateAndDoesNotThrow() throws Exception {
        when(session1.isOpen()).thenReturn(true);
        when(proofVaultService.retrieveProof(StorageKey.of(REAL_Y))).thenReturn(null);
        List<String> ids = new ArrayList<>();
        ids.add(null);
        ids.add(REAL_Y);
        String subId = subscriptionManager.subscribe(session1, SubscriptionKind.proof_state, ids);

        assertDoesNotThrow(() -> subscriptionManager.sendCurrentState(session1, subId, SubscriptionKind.proof_state));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1, times(1)).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getPayload().contains(REAL_Y),
                "The valid id alongside the null one must still get its state");
    }
}
