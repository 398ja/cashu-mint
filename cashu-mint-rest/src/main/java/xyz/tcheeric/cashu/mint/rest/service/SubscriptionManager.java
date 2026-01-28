package xyz.tcheeric.cashu.mint.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.nut17.*;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.nut.NUT17;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

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
    private final ProofVaultService proofVaultService;
    private final MintProtocolService mintProtocolService;
    private final String defaultUnit;

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

    public SubscriptionManager(ObjectMapper objectMapper,
                               ProofVaultService proofVaultService,
                               MintProtocolService mintProtocolService,
                               @Value("${cashu.default-unit:sat}") String defaultUnit) {
        this.objectMapper = objectMapper;
        this.proofVaultService = proofVaultService;
        this.mintProtocolService = mintProtocolService;
        this.defaultUnit = defaultUnit;
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

        // Create subscription
        Subscription subscription = new Subscription(subId, sessionId, kind, new HashSet<>(ids));
        subscriptionById.put(subId, subscription);

        // Add to session subscriptions atomically to avoid race with removeSession
        sessionSubscriptions.compute(sessionId, (key, subs) -> {
            if (subs == null) {
                subs = new CopyOnWriteArrayList<>();
            }
            subs.add(subscription);
            return subs;
        });

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

        String truncatedY = (y != null && y.length() > 8) ? y.substring(0, 8) : y;
        log.debug("proof_state_published y={} state={} subscriber_count={}", truncatedY, state, subIds.size());
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
     * <p>Per NUT-17 spec, new subscribers should receive the current state of subscribed items.
     *
     * @param session the WebSocket session
     * @param subId the subscription ID
     * @param kind the subscription kind
     */
    public void sendCurrentState(WebSocketSession session, String subId, SubscriptionKind kind) {
        Subscription subscription = subscriptionById.get(subId);
        if (subscription == null) {
            log.warn("current_state_requested sub_id={} not_found", subId);
            return;
        }

        log.debug("current_state_requested sub_id={} kind={} id_count={}", subId, kind, subscription.ids().size());

        switch (kind) {
            case proof_state -> sendCurrentProofStates(session, subId, subscription.ids());
            case bolt11_mint_quote -> sendCurrentMintQuoteStates(session, subId, subscription.ids());
            case bolt11_melt_quote -> sendCurrentMeltQuoteStates(session, subId, subscription.ids());
            default -> log.warn("current_state_unsupported_kind sub_id={} kind={}", subId, kind);
        }
    }

    /**
     * Sends current proof states using parallel vault queries via Virtual Threads.
     */
    private void sendCurrentProofStates(WebSocketSession session, String subId, Set<String> yValues) {
        if (yValues.isEmpty()) {
            return;
        }

        // Use Virtual Thread executor for parallel vault queries
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ProofStateResult>> futures = yValues.stream()
                    .map(y -> CompletableFuture.supplyAsync(() -> fetchProofState(y), executor))
                    .toList();

            // Wait for all and process results
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<ProofStateResult> future : futures) {
                ProofStateResult result = future.getNow(null);
                if (result != null && result.state() != null) {
                    JsonRpcNotification notification = NUT17.proofStateNotification(
                            subId, result.y(), result.state(), result.witness());
                    sendNotification(session, notification);
                    log.debug("current_proof_state_sent sub_id={} y_prefix={} state={}",
                            subId, result.y().length() > 8 ? result.y().substring(0, 8) : result.y(), result.state());
                }
            }
        }
    }

    private ProofStateResult fetchProofState(String y) {
        try {
            ProofEntity proofEntity = proofVaultService.retrieveProof(y);
            String state;
            String witness = null;
            if (proofEntity == null) {
                state = NUT07.UNSPENT;
            } else if (ProofEntity.STATE_PENDING.equals(proofEntity.getState())) {
                state = NUT07.PENDING;
                witness = proofEntity.getWitness();
            } else {
                state = NUT07.SPENT;
                witness = proofEntity.getWitness();
            }
            return new ProofStateResult(y, state, witness, null);
        } catch (Exception e) {
            log.error("current_proof_state_error y={} error={}",
                    y != null && y.length() > 8 ? y.substring(0, 8) : y, e.getMessage());
            return new ProofStateResult(y, null, null, e);
        }
    }

    private record ProofStateResult(String y, String state, String witness, Exception error) {}

    /**
     * Sends current mint quote states using parallel gateway queries via Virtual Threads.
     */
    private void sendCurrentMintQuoteStates(WebSocketSession session, String subId, Set<String> quoteIds) {
        if (quoteIds.isEmpty()) {
            return;
        }

        Gateway gateway;
        try {
            gateway = mintProtocolService.createGateway(PaymentMethod.BOLT11, defaultUnit);
        } catch (Exception e) {
            log.error("current_mint_quote_state_gateway_error sub_id={} error={}", subId, e.getMessage());
            return;
        }

        // Use Virtual Thread executor for parallel gateway queries
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<QuoteStateResult>> futures = quoteIds.stream()
                    .map(quoteId -> CompletableFuture.supplyAsync(
                            () -> fetchMintQuoteState(gateway, quoteId), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<QuoteStateResult> future : futures) {
                QuoteStateResult result = future.getNow(null);
                if (result != null && result.payload() != null) {
                    JsonRpcNotification notification = NUT17.quoteStateNotification(subId, result.payload());
                    sendNotification(session, notification);
                    log.debug("current_mint_quote_state_sent sub_id={} quote_id={} paid={}",
                            subId, result.quoteId(), result.payload().getPaid());
                }
            }
        }
    }

    private QuoteStateResult fetchMintQuoteState(Gateway gateway, String quoteId) {
        try {
            boolean paid = gateway.checkPaymentStatus(quoteId);
            String request = gateway.getRequest(quoteId);
            int expiry = gateway.getPaymentExpiry(quoteId);

            QuoteStatePayload payload = new QuoteStatePayload();
            payload.setQuoteId(quoteId);
            payload.setRequest(request);
            payload.setState(paid ? "PAID" : "UNPAID");
            payload.setPaid(paid);
            payload.setExpiry((long) expiry);

            return new QuoteStateResult(quoteId, payload, null);
        } catch (Exception e) {
            log.error("current_mint_quote_state_error quote_id={} error={}", quoteId, e.getMessage());
            return new QuoteStateResult(quoteId, null, e);
        }
    }

    /**
     * Sends current melt quote states using parallel gateway queries via Virtual Threads.
     */
    private void sendCurrentMeltQuoteStates(WebSocketSession session, String subId, Set<String> quoteIds) {
        if (quoteIds.isEmpty()) {
            return;
        }

        Gateway gateway;
        try {
            gateway = mintProtocolService.createGateway(PaymentMethod.BOLT11, defaultUnit);
        } catch (Exception e) {
            log.error("current_melt_quote_state_gateway_error sub_id={} error={}", subId, e.getMessage());
            return;
        }

        // Use Virtual Thread executor for parallel gateway queries
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<QuoteStateResult>> futures = quoteIds.stream()
                    .map(quoteId -> CompletableFuture.supplyAsync(
                            () -> fetchMeltQuoteState(gateway, quoteId), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<QuoteStateResult> future : futures) {
                QuoteStateResult result = future.getNow(null);
                if (result != null && result.payload() != null) {
                    JsonRpcNotification notification = NUT17.quoteStateNotification(subId, result.payload());
                    sendNotification(session, notification);
                    log.debug("current_melt_quote_state_sent sub_id={} quote_id={} paid={}",
                            subId, result.quoteId(), result.payload().getPaid());
                }
            }
        }
    }

    private QuoteStateResult fetchMeltQuoteState(Gateway gateway, String quoteId) {
        try {
            boolean paid = gateway.checkPaymentStatus(quoteId);
            int expiry = gateway.getPaymentExpiry(quoteId);

            QuoteStatePayload payload = new QuoteStatePayload();
            payload.setQuoteId(quoteId);
            payload.setState(paid ? "PAID" : "UNPAID");
            payload.setPaid(paid);
            payload.setExpiry((long) expiry);

            return new QuoteStateResult(quoteId, payload, null);
        } catch (Exception e) {
            log.error("current_melt_quote_state_error quote_id={} error={}", quoteId, e.getMessage());
            return new QuoteStateResult(quoteId, null, e);
        }
    }

    private record QuoteStateResult(String quoteId, QuoteStatePayload payload, Exception error) {}

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
