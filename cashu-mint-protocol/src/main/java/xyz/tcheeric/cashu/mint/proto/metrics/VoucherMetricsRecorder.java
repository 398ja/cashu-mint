package xyz.tcheeric.cashu.mint.proto.metrics;

import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;

/**
 * Typed recorder port for the voucher domain area — see
 * {@code docs/adr/0001-typed-metric-recorders.md}, issue #341.
 *
 * <p>Every metric the voucher area emits is declared here as a method. Domain
 * code never names a metric inline, so the declared set is the emitted set
 * (enforced by the catalogue contract test, issue #347).
 *
 * <p>Vocabulary: {@code voucher} is singular throughout, per the glossary. The
 * plural {@code vouchers_} prefix died with the dead metric classes deleted in
 * issue #339 and must not be reintroduced.
 *
 * <p><strong>Why the rate-limit breach counter carries no {@code principal}
 * label.</strong> It used to. Today's single {@code ADMIN} service account
 * makes that label bounded and identity-free, but the endpoint moves to
 * per-merchant authentication (#338), at which point the label becomes both a
 * cardinality problem (one series per merchant, unbounded, never reclaimed)
 * and a data-minimisation problem: spec 004 went to the trouble of keeping
 * merchant identity out of the read path with column-level {@code GRANT}s, and
 * a Prometheus label would route it straight back out — into a store with no
 * retention purge and no column grants. The breach rate is what pages someone;
 * <em>which</em> principal breached is a question for the logs, which are
 * access-controlled and already carry the principal. Recorded here rather than
 * in an ADR because the constraint belongs with the signature it shapes.
 *
 * <p>Implemented by {@code cashu-mint-observability}; reached through
 * {@link MetricRecorders#voucher()}, which never returns {@code null}.
 */
public interface VoucherMetricsRecorder {

    /**
     * A voucher mint was refused. Emits
     * {@code cashu_mint_voucher_rejected_total{reason=...}}.
     *
     * @param reason why the mint was refused; bounds the label domain
     */
    void rejected(VoucherRejectionReason reason);

    /**
     * A voucher was issued. Emits
     * {@code cashu_mint_voucher_issued_total{funding_source=...}}, the
     * per-funding-source success counter behind SC-006 liability
     * reconciliation (spec 003 FR-014).
     *
     * @param fundingSource funding row backing the issuance
     */
    void issued(VoucherFundingSource fundingSource);

    /**
     * An IOU-funded issuance was attempted, whatever the policy outcome
     * (spec 003 FR-014). Emits {@code cashu_mint_voucher_iou_issued_total}.
     * Denied attempts additionally emit {@link #rejected} with
     * {@link VoucherRejectionReason#IOU_NOT_PERMITTED}.
     */
    void iouIssuanceAttempted();

    /**
     * A funding row was lazily created from an accepted webhook event. Emits
     * {@code cashu_mint_voucher_lazy_funding_total}.
     */
    void lazyFundingCreated();

    /**
     * A caller exceeded the per-principal voucher rate limit (spec 003
     * FR-008). Emits {@code cashu_mint_voucher_rate_limit_breach_total} — no
     * principal label; see the class Javadoc.
     */
    void rateLimitBreach();
}
