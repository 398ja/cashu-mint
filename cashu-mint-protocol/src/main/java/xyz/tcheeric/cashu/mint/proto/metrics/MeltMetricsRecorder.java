package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Typed recorder port for the melt (NUT-05) domain area — see
 * {@code docs/adr/0001-typed-metric-recorders.md}.
 *
 * <p>Every metric the melt area emits is declared here as a method. Domain
 * code never names a metric inline, so the declared set and the emitted set
 * cannot drift: adding a metric means adding a method, and a method with no
 * production call site is a build failure (issue #347).
 *
 * <p>The Micrometer implementation lives in {@code cashu-mint-observability};
 * reach it through {@link MetricRecorders#melt()}, which returns a no-op when
 * observability is not wired.
 */
public interface MeltMetricsRecorder {

    /**
     * Spec 002 FR-001 / FR-009 — a melt was rejected because the supplied
     * proofs did not cover the invoice plus the exact fee reserve. Emits
     * {@code cashu_mint_melt_insufficient_input_total}.
     */
    void insufficientInput();

    /**
     * Spec 002 FR-006 — proofs could not be bound exclusively to the saga, so
     * the melt failed closed before any payment attempt. Emits
     * {@code cashu_mint_melt_proofs_not_bound_total}.
     */
    void proofsNotBound();
}
