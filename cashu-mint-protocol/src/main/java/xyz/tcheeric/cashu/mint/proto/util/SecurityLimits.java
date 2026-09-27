package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.List;

/**
 * Configurable security limits for mint operations.
 *
 * <p><b>Security:</b> These limits help prevent resource exhaustion attacks
 * (per Oracle Secure Coding Guidelines DOS-1) by bounding the maximum size
 * of user-controlled inputs.
 *
 * <p>Default values can be overridden via system properties:
 * <ul>
 *   <li>{@code cashu.limits.max-blinded-messages} - Max outputs per mint/swap request</li>
 *   <li>{@code cashu.limits.max-subscriptions-per-session} - Max WebSocket subscriptions</li>
 *   <li>{@code cashu.limits.max-subscription-ids} - Max IDs per subscription filter</li>
 * </ul>
 */
@Slf4j
public final class SecurityLimits {

    /**
     * Maximum number of blinded messages (outputs) allowed per mint or swap request.
     * Default: 1000 (allows up to ~10 BTC worth of smallest denominations)
     */
    public static final int MAX_BLINDED_MESSAGES = Integer.getInteger(
            "cashu.limits.max-blinded-messages", 1000);

    /**
     * Maximum number of subscriptions allowed per WebSocket session.
     * Default: 100
     */
    public static final int MAX_SUBSCRIPTIONS_PER_SESSION = Integer.getInteger(
            "cashu.limits.max-subscriptions-per-session", 100);

    /**
     * Maximum number of IDs allowed per subscription filter.
     * Default: 1000
     */
    public static final int MAX_SUBSCRIPTION_IDS = Integer.getInteger(
            "cashu.limits.max-subscription-ids", 1000);

    /**
     * Maximum number of proofs (inputs) allowed per swap or melt request.
     * Default: 1000
     */
    public static final int MAX_PROOFS = Integer.getInteger(
            "cashu.limits.max-proofs", 1000);

    private SecurityLimits() {
        // Utility class - prevent instantiation
    }

    /**
     * Refuses more inputs than {@link #MAX_PROOFS}, as NUT-00 {@code too_many_inputs} (11014).
     *
     * @param operation the task refusing, for the log line
     * @param inputs    the request's proofs; null is left to the caller's own validation
     */
    public static void requireWithinInputLimit(String operation, List<?> inputs) throws CashuErrorException {
        if (inputs != null && inputs.size() > MAX_PROOFS) {
            log.warn("{} too_many_inputs count={} max={}", operation, inputs.size(), MAX_PROOFS);
            throw new CashuErrorException(CashuErrorCode.too_many_inputs, "Maximum " + MAX_PROOFS + " inputs allowed");
        }
    }

    /**
     * Refuses more blinded messages than {@link #MAX_BLINDED_MESSAGES}, as NUT-00
     * {@code too_many_outputs} (11015).
     *
     * @param operation the task refusing, for the log line
     * @param outputs   the request's blinded messages; null is left to the caller's own validation
     */
    public static void requireWithinOutputLimit(String operation, List<?> outputs) throws CashuErrorException {
        if (outputs != null && outputs.size() > MAX_BLINDED_MESSAGES) {
            log.warn("{} too_many_outputs count={} max={}", operation, outputs.size(), MAX_BLINDED_MESSAGES);
            throw new CashuErrorException(CashuErrorCode.too_many_outputs,
                    "Maximum " + MAX_BLINDED_MESSAGES + " outputs allowed");
        }
    }
}
