package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.common.nut17.*;

import java.util.List;
import java.util.UUID;

/**
 * NUT-17: WebSocket subscriptions for real-time state updates.
 *
 * <p>Provides subscription management for:
 * <ul>
 *     <li>bolt11_mint_quote - Mint quote state changes (NUT-04)</li>
 *     <li>bolt11_melt_quote - Melt quote state changes (NUT-05)</li>
 *     <li>proof_state - Proof state changes (NUT-07)</li>
 * </ul>
 *
 * <p><b>Security:</b> Subscription IDs are generated using {@link UUID#randomUUID()}.
 * Clients can only unsubscribe from their own session's subscriptions.
 * State notifications only include information the client requested via filters.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/17.md">NUT-17 Specification</a>
 */
@Slf4j
@Nut(value = 17, description = "WebSocket subscriptions")
public final class NUT17 {

    private NUT17() {
        // Utility class - prevent instantiation
    }

    /**
     * JSON-RPC method for subscribing to events.
     */
    public static final String METHOD_SUBSCRIBE = "subscribe";

    /**
     * JSON-RPC method for unsubscribing from events.
     */
    public static final String METHOD_UNSUBSCRIBE = "unsubscribe";

    /**
     * Validates a subscription request.
     *
     * @param params the subscription parameters
     * @return true if valid, false otherwise
     */
    public static boolean validateSubscriptionParams(@NonNull SubscriptionParams params) {
        if (params.getKind() == null) {
            log.warn("nut17_validate invalid_params missing_kind");
            return false;
        }
        if (params.getFilters() == null || params.getFilters().isEmpty()) {
            log.warn("nut17_validate invalid_params missing_filters");
            return false;
        }
        for (SubscriptionFilter filter : params.getFilters()) {
            if (filter.getIds() == null || filter.getIds().isEmpty()) {
                log.warn("nut17_validate invalid_params empty_filter_ids");
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts all IDs from subscription filters.
     *
     * @param filters the subscription filters
     * @return flat list of all IDs
     */
    public static List<String> extractIds(@NonNull List<SubscriptionFilter> filters) {
        return filters.stream()
                .flatMap(f -> f.getIds().stream())
                .toList();
    }

    /**
     * Generates a unique subscription ID.
     *
     * @return a new subscription ID
     */
    public static String generateSubscriptionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Creates a JSON-RPC response for a successful subscription.
     *
     * @param requestId the original request ID
     * @param subId the assigned subscription ID
     * @return the response object
     */
    public static JsonRpcResponse subscribeResponse(@NonNull String requestId, @NonNull String subId) {
        return JsonRpcResponse.success(requestId, SubscriptionResult.ok(subId));
    }

    /**
     * Creates a JSON-RPC response for a successful unsubscription.
     *
     * @param requestId the original request ID
     * @param subId the unsubscribed subscription ID
     * @return the response object
     */
    public static JsonRpcResponse unsubscribeResponse(@NonNull String requestId, @NonNull String subId) {
        return JsonRpcResponse.success(requestId, SubscriptionResult.unsubscribed(subId));
    }

    /**
     * Creates a JSON-RPC error response.
     *
     * @param requestId the original request ID
     * @param code the error code
     * @param message the error message
     * @return the error response
     */
    public static JsonRpcResponse errorResponse(String requestId, int code, String message) {
        return JsonRpcResponse.error(requestId, code, message);
    }

    /**
     * Creates a proof state notification.
     *
     * @param subId the subscription ID
     * @param y the proof's Y value
     * @param state the new state (UNSPENT, PENDING, SPENT)
     * @param witness optional witness data
     * @return the notification object
     */
    public static JsonRpcNotification proofStateNotification(
            @NonNull String subId,
            @NonNull String y,
            @NonNull String state,
            String witness) {
        ProofStatePayload payload = new ProofStatePayload();
        payload.setY(y);
        payload.setState(state);
        payload.setWitness(witness);
        return JsonRpcNotification.of(subId, payload);
    }

    /**
     * Creates a quote state notification.
     *
     * @param subId the subscription ID
     * @param payload the quote state payload
     * @return the notification object
     */
    public static JsonRpcNotification quoteStateNotification(
            @NonNull String subId,
            @NonNull QuoteStatePayload payload) {
        return JsonRpcNotification.of(subId, payload);
    }
}
