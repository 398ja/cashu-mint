package xyz.tcheeric.cashu.mint.webhook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Payment notification received from payment-adapter.
 * This DTO matches the PaymentNotification sent by HttpMintWebhookForwarder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentNotification {

    /**
     * The quote identifier.
     */
    private String quoteId;

    /**
     * Payment method (e.g., "bolt11", "cash").
     */
    private String paymentMethod;

    /**
     * Amount paid in minor units (satoshis for BTC).
     */
    private Integer amount;

    /**
     * Payment preimage (for Lightning payments).
     */
    private String preimage;

    /**
     * Receipt ID (for cash payments).
     */
    private String receiptId;

    /**
     * Timestamp when payment was confirmed.
     */
    private Instant paidAt;

    /**
     * Get idempotency key for deduplication.
     */
    public String getIdempotencyKey() {
        return paymentMethod + ":" + quoteId;
    }
}
