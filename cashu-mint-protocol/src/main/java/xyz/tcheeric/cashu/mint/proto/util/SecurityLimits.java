package xyz.tcheeric.cashu.mint.proto.util;

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
}
