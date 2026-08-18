package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Typed recorder port for the mint/issuance domain area — see
 * {@code docs/adr/0001-typed-metric-recorders.md}, issue #342.
 *
 * <p>Every metric the mint path emits on its way to an issuance is declared
 * here as a method. Domain code never names a metric inline, so the declared
 * set is the emitted set (enforced by the catalogue contract test, #347).
 *
 * <p>Names follow the mandatory {@code cashu_mint_<area>_<event>_total}
 * convention with {@code issuance} as the area — {@code cashu_mint_mint_*}
 * would stutter, and every event here is a step on the road to issuing.
 *
 * <p>These counters used to carry a {@code path="mint"} label to separate them
 * from the melt path. The area now lives in the metric name, so the label was
 * a second, redundant encoding of the same fact and is gone.
 *
 * <p>Implemented by {@code cashu-mint-observability}; reached through
 * {@link MetricRecorders#issuance()}, which never returns {@code null}.
 */
public interface IssuanceMetricsRecorder {

    /**
     * A mint request was refused because the quote had expired (spec 001).
     * Emits {@code cashu_mint_issuance_quote_expired_total}.
     */
    void quoteExpired();

    /**
     * Spec 001 FR-001 — {@code sum(outputs.amount)} did not equal
     * {@code quote.amount}. Emits
     * {@code cashu_mint_issuance_amount_mismatch_total}.
     */
    void amountMismatch();

    /**
     * Spec 001 FR-010 — the {@code Gateway.getAmount(quoteId)} cross-check
     * before the {@code PAID → ISSUING} CAS failed. Emits
     * {@code cashu_mint_issuance_cross_check_failure_total}.
     */
    void crossCheckFailure();

    /**
     * NUT-19 idempotent replay — the same blinded outputs against an
     * {@code ISSUED} quote returned the previously signed promises. Emits
     * {@code cashu_mint_issuance_idempotent_replay_total}.
     */
    void idempotentReplay();

    /**
     * A caller exceeded the issuance rate limit. Emits
     * {@code cashu_mint_issuance_rate_limit_breach_total}.
     */
    void rateLimitBreach();
}
