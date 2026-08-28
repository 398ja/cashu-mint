package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Typed recorder port for NUT-12 DLEQ proof generation — see
 * {@code docs/adr/0001-typed-metric-recorders.md}.
 *
 * <p>The mint advertises NUT-12, so a blind signature without a DLEQ proof is
 * not a valid response and the signing request fails instead. That failure is
 * a signing outage rather than a client error, so it gets its own counter to
 * alert on rather than being buried in a WARN line.
 *
 * <p>Implemented by {@code cashu-mint-observability}; reached through
 * {@link MetricRecorders#dleq()}, which never returns {@code null}.
 */
public interface DleqMetricsRecorder {

    /**
     * DLEQ proof generation failed while signing a blinded message. Emits
     * {@code cashu_mint_dleq_generation_failures_total}.
     */
    void generationFailed();
}
