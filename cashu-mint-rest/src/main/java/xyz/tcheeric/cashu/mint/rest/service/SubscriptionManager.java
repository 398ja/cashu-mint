package xyz.tcheeric.cashu.mint.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.mint.proto.nut.NUT17;
import xyz.tcheeric.cashu.common.nut17.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages NUT-17 WebSocket subscriptions.
 *
 * <p>Thread-safe service that tracks active sessions and their subscriptions,
 * and routes notifications to matching subscribers.
 */
@Slf4j
@Service
public class SubscriptionManager {

    private final ObjectMapper objectMapper;

    /**
     * Maps session ID to active WebSocket session.
     */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Maps session ID to list of subscriptions for that session.
     */
    private final Map<String, List<Subscription>> sessionSubscriptions = new ConcurrentHashMap<>();

    /**
     * Maps subscription ID to its subscription details.
     */
    private final Map<String, Subscription> subscriptionById = new ConcurrentHashMap<>();

    /**
     * Indexes subscriptions by kind and target ID for efficient lookup.
     * Key format: "kind:targetId" (e.g., "proof_state:abc123")
     */
    private final Map<String, Set<String>> subscriptionIndex = new ConcurrentHashMap<>();

    public SubscriptionManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new subscription for a session.
     *
     * @param session the WebSocket session
     * @param kind the subscription kind
     * @param ids the IDs to subscribe to
     * @return the assigned subscription ID
     */
    public String subscribe(WebSocketSession session, SubscriptionKind kind, List<String> ids) {
        String sessionId = session.getId();
        String subId = NUT17.generateSubscriptionId();

        // Register session if not already present
        sessions.putIfAbsent(sessionId, session);
        sessionSubscriptions.computeIfAbsent(sessionId, k -> new ArrayList<>());

        // Create subscription
        Subscription subscription = new Subscription(subId, sessionId, kind, new HashSet<>(ids));
        subscriptionById.put(subId, subscription);
        sessionSubscriptions.get(sessionId).add(subscription);

        // Index for lookup
        for (String id : ids) {
            String indexKey = indexKey(kind, id);
            subscriptionIndex.computeIfAbsent(indexKey, k -> ConcurrentHashMap.newKeySet()).add(subId);
        }

        log.debug("subscription_created sub_id={} session_id={} kind={} id_count={}",
                subId, sessionId, kind, ids.size());

        return subId;
    }

    /**
     * Removes a subscription.
     *
     * @param sessionId the session ID
     * @param subId the subscription ID to remove
     * @return true if removed, false if not found
     */
    public boolean unsubscribe(String sessionId, String subId) {
        Subscription subscription = subscriptionById.get(subId);
        if (subscription == null || !subscription.sessionId().equals(sessionId)) {
            return false;
        }
        subscriptionById.remove(subId);

        // Remove from session subscriptions
        List<Subscription> subs = sessionSubscriptions.get(sessionId);
        if (subs != null) {
            subs.remove(subscription);
        }

        // Remove from index
        for (String id : subscription.ids()) {
            String indexKey = indexKey(subscription.kind(), id);
            Set<String> subIds = subscriptionIndex.get(indexKey);
            if (subIds != null) {
                subIds.remove(subId);
                if (subIds.isEmpty()) {
                    subscriptionIndex.remove(indexKey);
                }
            }
        }

        log.debug("subscription_removed sub_id={} session_id={}", subId, sessionId);
        return true;
    }

    /**
     * Removes all subscriptions for a session (called on disconnect).
     *
     * @param sessionId the session ID
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        List<Subscription> subs = sessionSubscriptions.remove(sessionId);
        if (subs == null) {
            return;
        }

        for (Subscription sub : subs) {
            subscriptionById.remove(sub.subId());
            for (String id : sub.ids()) {
                String indexKey = indexKey(sub.kind(), id);
                Set<String> subIds = subscriptionIndex.get(indexKey);
                if (subIds != null) {
                    subIds.remove(sub.subId());
                    if (subIds.isEmpty()) {
                        subscriptionIndex.remove(indexKey);
                    }
                }
            }
        }

        log.info("session_removed session_id={} subscription_count={}", sessionId, subs.size());
    }

    /**
     * Publishes a proof state change to all matching subscribers.
     *
     * @param y the proof's Y value
     * @param state the new state
     * @param witness optional witness data
     */
    public void publishProofState(String y, String state, String witness) {
        String indexKey = indexKey(SubscriptionKind.proof_state, y);
        Set<String> subIds = subscriptionIndex.get(indexKey);
        if (subIds == null || subIds.isEmpty()) {
            return;
        }

        for (String subId : subIds) {
            Subscription sub = subscriptionById.get(subId);
            if (sub == null) continue;

            WebSocketSession session = sessions.get(sub.sessionId());
            if (session == null || !session.isOpen()) continue;

            JsonRpcNotification notification = NUT17.proofStateNotification(subId, y, state, witness);
            sendNotification(session, notification);
        }

        log.debug("proof_state_published y={} state={} subscriber_count={}", y, state, subIds.size());
    }

    /**
     * Publishes a quote state change to all matching subscribers.
     *
     * @param kind the quote kind (bolt11_mint_quote or bolt11_melt_quote)
     * @param quoteId the quote ID
     * @param payload the quote state payload
     */
    public void publishQuoteState(SubscriptionKind kind, String quoteId, QuoteStatePayload payload) {
        String indexKey = indexKey(kind, quoteId);
        Set<String> subIds = subscriptionIndex.get(indexKey);
        if (subIds == null || subIds.isEmpty()) {
            return;
        }

        for (String subId : subIds) {
            Subscription sub = subscriptionById.get(subId);
            if (sub == null) continue;

            WebSocketSession session = sessions.get(sub.sessionId());
            if (session == null || !session.isOpen()) continue;

            JsonRpcNotification notification = NUT17.quoteStateNotification(subId, payload);
            sendNotification(session, notification);
        }

        log.debug("quote_state_published kind={} quote_id={} subscriber_count={}", kind, quoteId, subIds.size());
    }

    /**
     * Sends current state to a new subscriber.
     *
     * @param session the WebSocket session
     * @param subId the subscription ID
     * @param kind the subscription kind
     */
    public void sendCurrentState(WebSocketSession session, String subId, SubscriptionKind kind) {
        // This method can be extended to query current state from vault services
        // For now, we just acknowledge the subscription without sending current state
        // The client will receive notifications when state changes occur
        log.debug("current_state_requested sub_id={} kind={}", subId, kind);
    }

    /**
     * Gets the number of active sessions.
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Gets the total number of subscriptions.
     */
    public int getTotalSubscriptionCount() {
        return subscriptionById.size();
    }

    private String indexKey(SubscriptionKind kind, String id) {
        return kind.name() + ":" + id;
    }

    private void sendNotification(WebSocketSession session, JsonRpcNotification notification) {
        try {
            String json = objectMapper.writeValueAsString(notification);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("notification_send_error session_id={} error={}", session.getId(), e.getMessage());
        }
    }

    /**
     * Internal subscription record.
     */
    private record Subscription(
            String subId,
            String sessionId,
            SubscriptionKind kind,
            Set<String> ids
    ) {}
}
