package xyz.tcheeric.cashu.mint.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Payment notification received from payment-adapter.
 *
 * <p>Spec 008 — this DTO mirrors the on-the-wire contract emitted by
 * {@code payment-adapter} {@code HttpMintWebhookForwarder}, which serialises
 * snake_case JSON ({@code quote_id}, {@code payment_method}, {@code receipt_id},
 * {@code customer_pubkey}, {@code paid_at}) plus a {@code unit} field. The
 * snake_case {@code @JsonProperty} names are the canonical wire names; the
 * {@code @JsonAlias} camelCase names keep mint-originated producers (tests,
 * legacy callers) working. {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * accepts and ignores fields the mint doesn't consume (e.g. {@code customer_pubkey})
 * and is forward-compatible with future adapter additions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentNotification {

    /**
     * The quote identifier.
     */
    @JsonProperty("quote_id")
    @JsonAlias("quoteId")
    private String quoteId;

    /**
     * Payment method (e.g., "bolt11", "cash").
     */
    @JsonProperty("payment_method")
    @JsonAlias("paymentMethod")
    private String paymentMethod;

    /**
     * Amount paid in minor units (satoshis for BTC).
     */
    private Integer amount;

    /**
     * Currency unit the amount is denominated in (e.g. "sat"). Carried from
     * the adapter payload; the mint inherits the quote's unit for storage
     * today (unit-mismatch enforcement is a separate follow-up).
     */
    private String unit;

    /**
     * Payment preimage (for Lightning payments).
     */
    private String preimage;

    /**
     * Receipt ID (for cash payments).
     */
    @JsonProperty("receipt_id")
    @JsonAlias("receiptId")
    private String receiptId;

    /**
     * Timestamp when payment was confirmed.
     */
    @JsonProperty("paid_at")
    @JsonAlias("paidAt")
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
