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
     *
     * @deprecated Spec 001 FR-006: the durable idempotency key is
     *     {@code (provider, provider_event_id)}, not
     *     {@code paymentMethod:quoteId}. Retained until {@link QuoteStatusUpdater}'s
     *     legacy cache-only path is fully retired (T212 follow-up).
     */
    @Deprecated
    public String getIdempotencyKey() {
        return paymentMethod + ":" + quoteId;
    }

    /**
     * Spec 001 FR-006: returns the provider-side event id for the durable
     * {@code (provider, provider_event_id)} idempotency key. Resolution order:
     * preimage (Lightning) → receiptId (cash / other) → quoteId (last-resort
     * fallback so we always have a non-null key).
     */
    public String getProviderEventId() {
        if (preimage != null && !preimage.isBlank()) {
            return preimage;
        }
        if (receiptId != null && !receiptId.isBlank()) {
            return receiptId;
        }
        return quoteId;
    }
}
