package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason;

import java.util.EnumMap;
import java.util.Map;

/**
 * Micrometer implementation of the voucher-area recorder port (issue #341).
 *
 * <p>Every series is registered eagerly in the constructor — including one per
 * {@link VoucherRejectionReason} and one per {@link VoucherFundingSource} — so
 * the families appear in a scrape before the first rejection or issuance. A
 * family that only materialises on failure reads as "no data" on a dashboard,
 * which is indistinguishable from the metric being broken. Pre-registering
 * every enum value is what makes that affordable: the label domains are
 * closed, so the full series set is known at startup.
 *
 * <p>Names follow the mandatory {@code cashu_mint_<area>_<event>_total}
 * convention, with {@code voucher} singular.
 */
public class MicrometerVoucherMetricsRecorder implements VoucherMetricsRecorder {

    private static final String METRIC_PREFIX = "cashu_mint_voucher_";

    private final Map<VoucherRejectionReason, Counter> rejected =
            new EnumMap<>(VoucherRejectionReason.class);
    private final Map<VoucherFundingSource, Counter> issued =
            new EnumMap<>(VoucherFundingSource.class);
    private final Counter iouIssuanceAttempted;
    private final Counter lazyFundingCreated;
    private final Counter rateLimitBreach;

    public MicrometerVoucherMetricsRecorder(MeterRegistry registry) {
        for (VoucherRejectionReason reason : VoucherRejectionReason.values()) {
            rejected.put(reason, Counter.builder(METRIC_PREFIX + "rejected_total")
                    .description("Voucher mints refused, by reason")
                    .tag("reason", reason.label())
                    .register(registry));
        }
        for (VoucherFundingSource source : VoucherFundingSource.values()) {
            issued.put(source, Counter.builder(METRIC_PREFIX + "issued_total")
                    .description("Vouchers issued, by funding source")
                    .tag("funding_source", source.name())
                    .register(registry));
        }
        this.iouIssuanceAttempted = Counter.builder(METRIC_PREFIX + "iou_issued_total")
                .description("IOU-funded voucher issuances attempted, whatever the policy outcome")
                .register(registry);
        this.lazyFundingCreated = Counter.builder(METRIC_PREFIX + "lazy_funding_total")
                .description("Funding rows lazily created from an accepted webhook event")
                .register(registry);
        this.rateLimitBreach = Counter.builder(METRIC_PREFIX + "rate_limit_breach_total")
                .description("Voucher requests rejected by the per-principal rate limit")
                .register(registry);
    }

    @Override
    public void rejected(VoucherRejectionReason reason) {
        rejected.get(reason).increment();
    }

    @Override
    public void issued(VoucherFundingSource fundingSource) {
        issued.get(fundingSource).increment();
    }

    @Override
    public void iouIssuanceAttempted() {
        iouIssuanceAttempted.increment();
    }

    @Override
    public void lazyFundingCreated() {
        lazyFundingCreated.increment();
    }

    @Override
    public void rateLimitBreach() {
        rateLimitBreach.increment();
    }
}
