package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherRedemptionCapMode;

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
    private final Map<VoucherIssuerBindingMode, Counter> issuerUntrusted =
            new EnumMap<>(VoucherIssuerBindingMode.class);
    private final Map<VoucherIssuerBindingMode, Counter> unsignedVoucher =
            new EnumMap<>(VoucherIssuerBindingMode.class);
    private final Map<VoucherRedemptionCapMode, Counter> overspend =
            new EnumMap<>(VoucherRedemptionCapMode.class);
    private final Map<String, Counter> redemptionDeclared = new java.util.HashMap<>();
    private final Counter iouIssuanceAttempted;
    private final Counter lazyFundingCreated;
    private final Counter fundingRecovered;
    private final Counter fundingReconcileFailed;
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
        // Both outcomes pre-registered so the family is scrapeable before the
        // first sweep recovers anything: a series that only appears once
        // something breaks reads as a broken exporter on a dashboard.
        this.fundingRecovered = Counter.builder(METRIC_PREFIX + "funding_reconciled_total")
                .description("Paid-but-unfunded voucher quotes resolved by the reconciler, by outcome")
                .tag("outcome", "recovered")
                .register(registry);
        this.fundingReconcileFailed = Counter.builder(METRIC_PREFIX + "funding_reconciled_total")
                .description("Paid-but-unfunded voucher quotes resolved by the reconciler, by outcome")
                .tag("outcome", "failed")
                .register(registry);
        this.rateLimitBreach = Counter.builder(METRIC_PREFIX + "rate_limit_breach_total")
                .description("Voucher requests rejected by the per-principal rate limit")
                .register(registry);
        registerPerCheckingMode(registry, issuerUntrusted, "issuer_untrusted_total",
                "Signed vouchers whose signing key is not trusted for their issuer, by binding mode");
        registerPerCheckingMode(registry, unsignedVoucher, "unsigned_total",
                "Voucher proofs carrying no issuer signature, by binding mode");
        // cashu-mint#540. One series per checking mode: log counts would-refuse, enforce counts
        // refusals. The alert fires on either, so a log-mode hit is seen before enforce.
        for (VoucherRedemptionCapMode mode : VoucherRedemptionCapMode.values()) {
            if (mode == VoucherRedemptionCapMode.OFF) {
                continue;
            }
            overspend.put(mode, Counter.builder(METRIC_PREFIX + "overspend_refused_total")
                    .description("Declared voucher redemptions over the coupon's issued sats, by cap mode")
                    .tag("mode", mode.label())
                    .register(registry));
        }
        // accepted, refused, cap_unknown: what a trusted declaration did. reused: a NUT-19 retry
        // found its own claim standing (review §2a). ignored: log mode dropped a malformed,
        // untrusted or mismatched declaration (review §1.2d). released: a redeemer gave a
        // declaration back because its credit was undone (review §1.2a).
        for (String outcome : new String[] {"accepted", "refused", "cap_unknown", "reused", "ignored", "released"}) {
            redemptionDeclared.put(outcome, Counter.builder(METRIC_PREFIX + "redemption_declared_total")
                    .description("Voucher redemptions declared by a redeemer, by outcome")
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    @Override
    public void overspend(VoucherRedemptionCapMode mode) {
        Counter counter = overspend.get(mode);
        if (counter != null) {
            counter.increment();
        }
    }

    @Override
    public void redemptionDeclared(String outcome) {
        Counter counter = redemptionDeclared.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * One series per mode that checks vouchers. {@code off} never checks, so it never counts,
     * and a series that can only read zero would suggest otherwise.
     */
    private static void registerPerCheckingMode(MeterRegistry registry,
                                                Map<VoucherIssuerBindingMode, Counter> counters,
                                                String name, String description) {
        for (VoucherIssuerBindingMode mode : VoucherIssuerBindingMode.values()) {
            if (mode == VoucherIssuerBindingMode.OFF) {
                continue;
            }
            counters.put(mode, Counter.builder(METRIC_PREFIX + name)
                    .description(description)
                    .tag("mode", mode.label())
                    .register(registry));
        }
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
    public void fundingReconciled(boolean recovered) {
        (recovered ? fundingRecovered : fundingReconcileFailed).increment();
    }

    @Override
    public void rateLimitBreach() {
        rateLimitBreach.increment();
    }

    @Override
    public void issuerUntrusted(VoucherIssuerBindingMode mode) {
        incrementIfChecked(issuerUntrusted, mode);
    }

    @Override
    public void unsignedVoucher(VoucherIssuerBindingMode mode) {
        incrementIfChecked(unsignedVoucher, mode);
    }

    private static void incrementIfChecked(Map<VoucherIssuerBindingMode, Counter> counters,
                                           VoucherIssuerBindingMode mode) {
        Counter counter = counters.get(mode);
        if (counter != null) {
            counter.increment();
        }
    }
}
