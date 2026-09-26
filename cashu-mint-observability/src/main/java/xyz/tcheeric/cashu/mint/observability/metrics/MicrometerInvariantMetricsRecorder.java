package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.metrics.InvariantMetricsRecorder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Micrometer implementation of the invariant recorder port (issue #343),
 * backing the DB-derived gauges of ADR 0002.
 */
public class MicrometerInvariantMetricsRecorder implements InvariantMetricsRecorder {

    private final MeterRegistry registry;
    private final Counter pollFailures;

    /**
     * Holds each issued-amount supplier strongly. A function counter only keeps a weak
     * reference to the object it reads, so without this the supplier could be collected and
     * the series would silently start reporting NaN.
     */
    private final Map<String, Supplier<Number>> issuedAmountSources = new ConcurrentHashMap<>();

    public MicrometerInvariantMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
        this.pollFailures = Counter.builder("cashu_mint_invariant_poll_failures_total")
                .description("Invariant poll attempts that failed; a non-zero rate means the gauges are stale")
                .register(registry);
    }

    @Override
    public void bindStuckPaymentUnknown(Supplier<Number> value) {
        Gauge.builder("cashu_mint_melt_stuck_payment_unknown", value)
                .description("Melt sagas stuck in PAYMENT_UNKNOWN past cashu.mint.melt.payment-unknown-ttl "
                        + "(see MeltSagaJpaRepository#countStuckPaymentUnknown)")
                .register(registry);
    }

    @Override
    public void bindPaymentSentBurnFailed(Supplier<Number> value) {
        Gauge.builder("cashu_mint_melt_payment_sent_burn_failed", value)
                .description("Melt sagas whose payment settled but whose proofs were never burned "
                        + "(see MeltSagaJpaRepository#countPaymentSentBurnFailed)")
                .register(registry);
    }

    @Override
    public void bindOrphanIssuance(Supplier<Number> value) {
        Gauge.builder("cashu_mint_voucher_orphan_issuance", value)
                .description("Issued voucher quotes with no funding row "
                        + "(see VoucherIssuanceJpaRepository#countOrphanIssuance)")
                .register(registry);
    }

    @Override
    public void bindPaidUnfunded(Supplier<Number> value) {
        Gauge.builder("cashu_mint_voucher_paid_unfunded", value)
                .description("Voucher quotes still UNFUNDED despite an accepted payment event "
                        + "(see VoucherQuoteJpaRepository#countPaidUnfunded)")
                .register(registry);
    }

    @Override
    public void bindUnfundedWithoutWebhook(Supplier<Number> value) {
        Gauge.builder("cashu_mint_voucher_unfunded_without_webhook", value)
                .description("Voucher quotes UNFUNDED with no payment event at all — the mint and "
                        + "the payment adapter disagree and the sweep cannot resolve it "
                        + "(see VoucherQuoteJpaRepository#countUnfundedWithoutWebhook)")
                .register(registry);
    }

    @Override
    public void bindUnfundedRejectedOnly(Supplier<Number> value) {
        Gauge.builder("cashu_mint_voucher_unfunded_rejected_only", value)
                .description("Voucher quotes UNFUNDED whose only payment events were rejected — the "
                        + "mint was told and said no "
                        + "(see VoucherQuoteJpaRepository#countUnfundedRejectedOnly)")
                .register(registry);
    }

    @Override
    public void bindTerminalUnsettled(Supplier<Number> value) {
        Gauge.builder("cashu_mint_melt_terminal_unsettled", value)
                .description("Melt sagas terminal via the reconciler with no proof-settlement "
                        + "recorded — proofs may be stuck PENDING and nothing will retry "
                        + "(see MeltSagaJpaRepository#countTerminalWithUnsettledProofs)")
                .register(registry);
    }

    @Override
    public void bindPaidUnissued(Supplier<Number> value) {
        Gauge.builder("cashu_mint_quote_paid_unissued", value)
                .description("Mint quotes in PAID past the stranded TTL: payment accepted, "
                        + "nothing issued, and nothing will issue it without the client "
                        + "returning (see MintQuoteJpaRepository#countPaidUnissued)")
                .register(registry);
    }

    /**
     * Registered as a function counter rather than a gauge so the exposition keeps its
     * {@code _total} suffix: the Prometheus registry strips it from gauges. The value is a
     * sum over an append-only table, so it satisfies a counter's never-decreasing contract.
     */
    @Override
    public void bindIssuedAmount(String keysetId, Supplier<Number> value) {
        issuedAmountSources.put(keysetId, value);
        FunctionCounter.builder("cashu_mint_issued_amount_total", value,
                        supplier -> supplier.get().doubleValue())
                .description("Total face value the mint has signed per keyset, summed from the "
                        + "durable blind_signature record "
                        + "(see BlindSignatureJpaRepository#sumIssuedAmountByKeyset)")
                .tag("keyset", keysetId)
                .register(registry);
    }

    @Override
    public void pollFailed() {
        pollFailures.increment();
    }
}
