package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for Cashu Mint voucher operations.
 *
 * <p>This class provides metrics instrumentation for:
 * <ul>
 *   <li>Voucher quote creation with percentage-based fees</li>
 *   <li>Voucher issuance and redemption</li>
 *   <li>Voucher rejection tracking</li>
 *   <li>Fee collection from voucher quotes</li>
 * </ul>
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_vouchers_*}
 */
@Slf4j
public class VoucherMetrics {

    private static final String METRIC_PREFIX = "cashu_mint_vouchers_";

    private final MeterRegistry registry;

    // Atomic counters for gauges
    private final AtomicLong activeVoucherQuotes;

    // Global counters
    private final Counter vouchersIssuedTotal;
    private final Counter vouchersRedeemedTotal;
    private final Counter voucherFaceValueIssuedTotal;
    private final Counter voucherFaceValueRedeemedTotal;
    private final Counter voucherFeesCollectedTotal;

    // Rejection counters by reason
    private final ConcurrentHashMap<String, Counter> rejectionCounters = new ConcurrentHashMap<>();

    // Timers
    private final Timer voucherQuoteTimer;
    private final Timer voucherRedemptionTimer;

    /**
     * Creates a new VoucherMetrics instance.
     *
     * @param registry the Micrometer registry to use
     */
    public VoucherMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Initialize atomic values for gauges
        this.activeVoucherQuotes = new AtomicLong(0);

        // Register gauge for active voucher quotes
        Gauge.builder(METRIC_PREFIX + "quotes_active", activeVoucherQuotes, AtomicLong::get)
                .description("Number of active voucher quotes")
                .register(registry);

        // Global counters
        this.vouchersIssuedTotal = Counter.builder(METRIC_PREFIX + "issued_total")
                .description("Total vouchers issued")
                .register(registry);

        this.vouchersRedeemedTotal = Counter.builder(METRIC_PREFIX + "redeemed_total")
                .description("Total vouchers redeemed")
                .register(registry);

        this.voucherFaceValueIssuedTotal = Counter.builder(METRIC_PREFIX + "face_value_issued_total")
                .description("Total face value of vouchers issued")
                .tag("unit", "sat")
                .register(registry);

        this.voucherFaceValueRedeemedTotal = Counter.builder(METRIC_PREFIX + "face_value_redeemed_total")
                .description("Total face value of vouchers redeemed")
                .tag("unit", "sat")
                .register(registry);

        this.voucherFeesCollectedTotal = Counter.builder(METRIC_PREFIX + "fees_collected_total")
                .description("Total fees collected from voucher quotes")
                .tag("unit", "sat")
                .register(registry);

        // Timers
        this.voucherQuoteTimer = Timer.builder(METRIC_PREFIX + "quote_duration_seconds")
                .description("Voucher quote processing duration")
                .register(registry);

        this.voucherRedemptionTimer = Timer.builder(METRIC_PREFIX + "redemption_duration_seconds")
                .description("Voucher redemption processing duration")
                .register(registry);

        log.debug("VoucherMetrics initialized");
    }

    /**
     * Records a voucher quote being created.
     *
     * @param faceValue the voucher face value in sats
     * @param feeAmount the fee amount charged (face value * fee percentage)
     */
    public void recordVoucherQuoteCreated(long faceValue, long feeAmount) {
        activeVoucherQuotes.incrementAndGet();
        voucherFeesCollectedTotal.increment(feeAmount);
        log.trace("Recorded voucher quote created: faceValue={}, fee={}", faceValue, feeAmount);
    }

    /**
     * Records a voucher being issued (quote completed, tokens minted).
     *
     * @param faceValue the voucher face value in sats
     */
    public void recordVoucherIssued(long faceValue) {
        vouchersIssuedTotal.increment();
        voucherFaceValueIssuedTotal.increment(faceValue);
        activeVoucherQuotes.decrementAndGet();
        log.trace("Recorded voucher issued: faceValue={}", faceValue);
    }

    /**
     * Records a voucher being redeemed (tokens spent).
     *
     * @param faceValue the voucher face value in sats
     */
    public void recordVoucherRedeemed(long faceValue) {
        vouchersRedeemedTotal.increment();
        voucherFaceValueRedeemedTotal.increment(faceValue);
        log.trace("Recorded voucher redeemed: faceValue={}", faceValue);
    }

    /**
     * Records a voucher being rejected.
     *
     * @param reason the rejection reason (e.g., "expired", "invalid_signature", "already_redeemed")
     */
    public void recordVoucherRejected(String reason) {
        getRejectionCounter(reason).increment();
        log.trace("Recorded voucher rejected: reason={}", reason);
    }

    /**
     * Records a voucher quote expiring.
     */
    public void recordVoucherQuoteExpired() {
        activeVoucherQuotes.decrementAndGet();
        getRejectionCounter("quote_expired").increment();
        log.trace("Recorded voucher quote expired");
    }

    /**
     * Records the processing time for a voucher quote.
     *
     * @param durationNanos duration in nanoseconds
     */
    public void recordVoucherQuoteTime(long durationNanos) {
        voucherQuoteTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records the processing time for a voucher redemption.
     *
     * @param durationNanos duration in nanoseconds
     */
    public void recordVoucherRedemptionTime(long durationNanos) {
        voucherRedemptionTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Starts a timer sample for measuring voucher operations.
     *
     * @return a new timer sample
     */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops a timer sample and records to the voucher quote timer.
     *
     * @param sample the timer sample
     * @return the duration in nanoseconds
     */
    public long stopQuoteTimer(Timer.Sample sample) {
        return sample.stop(voucherQuoteTimer);
    }

    /**
     * Stops a timer sample and records to the voucher redemption timer.
     *
     * @param sample the timer sample
     * @return the duration in nanoseconds
     */
    public long stopRedemptionTimer(Timer.Sample sample) {
        return sample.stop(voucherRedemptionTimer);
    }

    /**
     * Gets the current number of active voucher quotes.
     *
     * @return active voucher quote count
     */
    public long getActiveVoucherQuotes() {
        return activeVoucherQuotes.get();
    }

    /**
     * Gets the total vouchers issued.
     *
     * @return voucher issued count
     */
    public double getVouchersIssued() {
        return vouchersIssuedTotal.count();
    }

    /**
     * Gets the total vouchers redeemed.
     *
     * @return voucher redeemed count
     */
    public double getVouchersRedeemed() {
        return vouchersRedeemedTotal.count();
    }

    /**
     * Gets the total fees collected from voucher quotes.
     *
     * @return fees collected in sats
     */
    public double getFeesCollected() {
        return voucherFeesCollectedTotal.count();
    }

    /**
     * Sets the active voucher quote count (for initialization from DB).
     *
     * @param count the count
     */
    public void setActiveVoucherQuotes(long count) {
        activeVoucherQuotes.set(count);
    }

    // Helper methods

    private Counter getRejectionCounter(String reason) {
        return rejectionCounters.computeIfAbsent(reason, r ->
                Counter.builder(METRIC_PREFIX + "rejected_total")
                        .description("Vouchers rejected")
                        .tag("reason", r)
                        .register(registry));
    }
}
