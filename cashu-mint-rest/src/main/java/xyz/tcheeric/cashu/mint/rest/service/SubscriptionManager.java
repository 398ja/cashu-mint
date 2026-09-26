package xyz.tcheeric.cashu.mint.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.nut17.*;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.nut.NUT17;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.SecurityLimits;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.util.QuoteExpiry;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Manages NUT-17 WebSocket subscriptions.
 *
 * <p>Thread-safe service that tracks active sessions and their subscriptions,
 * and routes notifications to matching subscribers.
 *
 * <p><b>Security:</b> Subscription limits are enforced to prevent resource
 * exhaustion attacks (per Oracle Secure Coding Guidelines DOS-1).
 */
@Slf4j
@Service
public final class SubscriptionManager {

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
     * <p><b>Security:</b> Subscription limits are enforced to prevent resource exhaustion
     * attacks (per Oracle Secure Coding Guidelines DOS-1).
     *
     * @param session the WebSocket session
     * @param kind the subscription kind
     * @param ids the IDs to subscribe to
     * @return the assigned subscription ID
     * @throws IllegalArgumentException if limits are exceeded
     */
    public String subscribe(WebSocketSession session, SubscriptionKind kind, List<String> ids) {
        String sessionId = session.getId();

        // Security limit checks (per Oracle Secure Coding Guidelines DOS-1)
        List<Subscription> existingSubs = sessionSubscriptions.get(sessionId);
        if (existingSubs != null && existingSubs.size() >= SecurityLimits.MAX_SUBSCRIPTIONS_PER_SESSION) {
            log.warn("subscription_limit_exceeded session_id={} current={} max={}",
                    sessionId, existingSubs.size(), SecurityLimits.MAX_SUBSCRIPTIONS_PER_SESSION);
            throw new IllegalArgumentException("Maximum " + SecurityLimits.MAX_SUBSCRIPTIONS_PER_SESSION
                    + " subscriptions per session allowed");
        }

        if (ids != null && ids.size() > SecurityLimits.MAX_SUBSCRIPTION_IDS) {
            log.warn("subscription_ids_limit_exceeded session_id={} count={} max={}",
                    sessionId, ids.size(), SecurityLimits.MAX_SUBSCRIPTION_IDS);
            throw new IllegalArgumentException("Maximum " + SecurityLimits.MAX_SUBSCRIPTION_IDS
                    + " IDs per subscription allowed");
        }

        String subId = NUT17.generateSubscriptionId();

        // Register session if not already present
        sessions.putIfAbsent(sessionId, session);

        // Create subscription
        Subscription subscription = Subscription.of(subId, sessionId, kind, ids != null ? ids : List.of());
        Set<String> safeIds = subscription.ids();
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
        for (String id : safeIds) {
            String indexKey = indexKey(kind, id);
            subscriptionIndex.computeIfAbsent(indexKey, k -> ConcurrentHashMap.newKeySet()).add(subId);
        }

        log.debug("subscription_created sub_id={} session_id={} kind={} id_count={}",
                subId, sessionId, kind, safeIds.size());

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
            unindex(subscription.kind(), id, subId);
        }

        log.debug("subscription_removed sub_id={} session_id={}", subId, sessionId);
        return true;
    }

    /**
     * Removes all subscriptions for a session (called on disconnect).
     *
     * @param sessionId the session ID
     */
    @SuppressWarnings("resource") // WebSocketSession lifecycle is managed by the Spring framework, not by us
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        List<Subscription> subs = sessionSubscriptions.remove(sessionId);
        if (subs == null) {
            return;
        }

        for (Subscription sub : subs) {
            subscriptionById.remove(sub.subId());
            for (String id : sub.ids()) {
                unindex(sub.kind(), id, sub.subId());
            }
        }

        log.info("session_removed session_id={} subscription_count={}", sessionId, subs.size());
    }

    /**
     * Drops one subscription from one index entry, removing the entry once it is empty. Atomic per
     * key: a separate emptiness check and remove could drop a subscription another thread indexed
     * under the same key in between, and that subscriber would silently stop receiving updates.
     */
    private void unindex(SubscriptionKind kind, String id, String subId) {
        subscriptionIndex.computeIfPresent(indexKey(kind, id), (key, subIds) -> {
            subIds.remove(subId);
            return subIds.isEmpty() ? null : subIds;
        });
    }

    /** How many subscriptions are indexed under this target. Package-private for tests. */
    int indexedSubscriberCount(SubscriptionKind kind, String id) {
        Set<String> subIds = subscriptionIndex.get(indexKey(kind, id));
        return subIds == null ? 0 : subIds.size();
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

            JsonRpcNotification notification =
                    NUT17.proofStateNotification(subId, sub.spellingOf(y), state, witness);
            sendNotification(session, notification);
        }

        log.debug("proof_state_published y={} state={} subscriber_count={}", yPrefix(y), state, subIds.size());
    }

    /**
     * Publishes a quote state change to all matching subscribers.
     *
     * @param kind the quote kind (bolt11_mint_quote or bolt11_melt_quote)
     * @param quoteId the quote ID
     * @param payload the quote state payload
     */
    public void publishQuoteState(SubscriptionKind kind, String quoteId, QuoteStatePayload payload) {
        publish(kind, quoteId, subId -> NUT17.quoteStateNotification(subId, payload));
    }

    /**
     * Publishes a mint quote's state to its {@code bolt11_mint_quote} subscribers.
     *
     * <p>NUT-17 carries the NUT-04 {@code MintQuoteResponse} itself, so the payload is the same
     * response {@code GET /v1/mint/quote/bolt11/{id}} returns, accounting fields included
     * (cashu-mint#500).
     *
     * @param quote the quote as the bolt11 status route reports it
     */
    public void publishMintQuoteState(PostMintQuoteResponse quote) {
        publish(SubscriptionKind.bolt11_mint_quote, quote.getQuoteId(),
                subId -> NUT17.mintQuoteStateNotification(subId, quote));
    }

    private void publish(SubscriptionKind kind, String quoteId,
                         Function<String, JsonRpcNotification> notificationFor) {
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

            sendNotification(session, notificationFor.apply(subId));
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
                            subId, yPrefix(result.y()), result.state());
                }
            }
        }
    }

    private ProofStateResult fetchProofState(String y) {
        StorageKey key;
        try {
            // NUT-17 delivers the hash-to-curve point Y, exactly as NUT-07 does, and Y is the
            // storage key (#485, #487). An id that is not a curve point names no proof: it gets no
            // state rather than UNSPENT, and as client input it is not an error on our side.
            key = StorageKey.of(y);
        } catch (IllegalArgumentException notACurvePoint) {
            log.warn("current_proof_state_invalid_y y_prefix={}", yPrefix(y));
            return new ProofStateResult(y, null, null, notACurvePoint);
        }
        try {
            ProofEntity proofEntity = proofVaultService.retrieveProof(key);
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
                    yPrefix(y), e.getMessage());
            return new ProofStateResult(y, null, null, e);
        }
    }

    private record ProofStateResult(String y, String state, String witness, Exception error) {}

    /** The first eight characters of a Y, enough to correlate log lines without logging it whole. */
    private static String yPrefix(String y) {
        return y != null && y.length() > 8 ? y.substring(0, 8) : y;
    }

    /**
     * Sends current mint quote states, looked up in parallel on Virtual Threads.
     *
     * <p>Each state comes from the same lookup that serves
     * {@code GET /v1/mint/quote/bolt11/{id}}, so the WebSocket and HTTP channels cannot disagree
     * (cashu-mint#500): a voucher quote id is refused here as it is there, the state follows the
     * quote's lifecycle (so {@code ISSUED} is reported), and the payload is the full NUT-04
     * response with {@code amount_paid}, {@code amount_issued} and {@code updated_at}. A quote the
     * route refuses produces no notification at all.
     */
    private void sendCurrentMintQuoteStates(WebSocketSession session, String subId, Set<String> quoteIds) {
        if (quoteIds.isEmpty()) {
            return;
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Optional<PostMintQuoteResponse>>> futures = quoteIds.stream()
                    .map(quoteId -> CompletableFuture.supplyAsync(
                            () -> fetchMintQuoteState(quoteId), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<Optional<PostMintQuoteResponse>> future : futures) {
                future.getNow(Optional.empty()).ifPresent(quote -> {
                    sendNotification(session, NUT17.mintQuoteStateNotification(subId, quote));
                    log.debug("current_mint_quote_state_sent sub_id={} quote_id={} state={}",
                            subId, quote.getQuoteId(), quote.getState());
                });
            }
        }
    }

    /**
     * The quote's NUT-04 state as the bolt11 status route reports it, or empty when that route
     * refuses it (a voucher quote, an unknown id) or the lookup fails. Goes through
     * {@link NUT04#quotePaymentStatus}, with the same arguments as the route, so the two channels
     * share one lookup rather than two that could drift. No unit is passed, as the route passes
     * none: the quote's own unit then picks the gateway and is reported, where forcing the
     * configured default reported a {@code usd} quote as {@code sat}.
     */
    private Optional<PostMintQuoteResponse> fetchMintQuoteState(String quoteId) {
        try {
            return Optional.of(NUT04.quotePaymentStatus(quoteId, PaymentMethod.BOLT11));
        } catch (CashuErrorException refused) {
            log.info("current_mint_quote_state_refused quote_id={} reason={}", quoteId, refused.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("current_mint_quote_state_error quote_id={} error={}", quoteId, e.getMessage());
            return Optional.empty();
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
            int expiry = QuoteExpiry.ofQuote(gateway, quoteId);

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

    /**
     * The key a subscription target is indexed under.
     *
     * <p>A proof-state target is a hex point, and hex is case-insensitive: the mint publishes
     * lowercase Ys, so a wallet that subscribed with uppercase used to get its initial state (the
     * vault lookup normalises) and then never a single update (cashu-mint#511). Quote ids are
     * opaque and matched exactly.
     */
    private static String indexKey(SubscriptionKind kind, String id) {
        return kind.name() + ":" + normalisedId(kind, id);
    }

    private static String normalisedId(SubscriptionKind kind, String id) {
        return kind == SubscriptionKind.proof_state && id != null ? id.toLowerCase(Locale.ROOT) : id;
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
            Set<String> ids,
            Map<String, String> spellingByTarget
    ) {
        /**
         * Builds a subscription with one id per target: the same Y written in two cases is one
         * proof, so it keeps the first spelling and yields one initial state, not two.
         */
        static Subscription of(String subId, String sessionId, SubscriptionKind kind, List<String> ids) {
            // Not Map.copyOf: a subscriber can send a null id, and the index tolerates one.
            Map<String, String> spellingByTarget = new LinkedHashMap<>();
            for (String id : ids) {
                spellingByTarget.putIfAbsent(normalisedId(kind, id), id);
            }
            Set<String> distinctIds = new LinkedHashSet<>(spellingByTarget.values());
            return new Subscription(subId, sessionId, kind, Collections.unmodifiableSet(distinctIds),
                    Collections.unmodifiableMap(spellingByTarget));
        }

        /**
         * The target as this subscriber wrote it. A notification echoes the subscriber's own
         * spelling, so a wallet that matches payloads against the Ys it sent still recognises one
         * published in the mint's lowercase form.
         */
        String spellingOf(String id) {
            return spellingByTarget.getOrDefault(normalisedId(kind, id), id);
        }
    }
}
