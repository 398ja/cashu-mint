package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Typed recorder port for per-keyset redemption cutoffs (cashu-mint#551) — see
 * {@code docs/adr/0001-typed-metric-recorders.md}.
 *
 * <p>After a keyset's cutoff the mint refuses its proofs. A refusal is either a late honest
 * holder who missed the announced window or someone presenting a forged proof, and the two look
 * the same at the mint. Counting them per keyset is what makes the late holders visible, so an
 * operator can judge whether a window was long enough.
 *
 * <p>The {@code keyset} label is bounded: only keysets an operator retired ever carry a cutoff,
 * a handful per mint over its lifetime.
 *
 * <p>Implemented by {@code cashu-mint-observability}; reached through
 * {@link MetricRecorders#keysetRedemption()}, which never returns {@code null}.
 */
public interface KeysetRedemptionMetricsRecorder {

    /**
     * An input on a keyset past its redemption cutoff was refused. Emits
     * {@code cashu_mint_keyset_redemption_refused_total{keyset,operation}}.
     *
     * @param keysetId  the retired keyset the input named
     * @param operation the operation that refused it: {@code swap} or {@code melt}
     */
    void refused(String keysetId, String operation);
}
