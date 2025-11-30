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
 * Metrics for Cashu Mint quote operations.
 *
 * <p>This class provides metrics instrumentation for:
 * <ul>
 *   <li>Quote creation (mint and melt quotes)</li>
 *   <li>Quote completion/fulfillment</li>
 *   <li>Quote expiration</li>
 *   <li>Quote processing duration</li>
 *   <li>Active quote count</li>
 * </ul>
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_quotes_*}
 */
@Slf4j
public class QuoteMetrics {

    private static final String METRIC_PREFIX = "cashu_mint_quotes_";

    private final MeterRegistry registry;

    // Atomic counters for gauges
    private final AtomicLong activeMintQuotes;
    private final AtomicLong activeMeltQuotes;

    // Cached counters by type and method
    private final ConcurrentHashMap<String, Counter> createdCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> completedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> expiredCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> failedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> processingTimers = new ConcurrentHashMap<>();

    // Amount counters
    private final Counter mintQuoteAmountTotal;
    private final Counter meltQuoteAmountTotal;
    private final Counter mintQuoteCompletedAmountTotal;
    private final Counter meltQuoteCompletedAmountTotal;

    /**
     * Creates a new QuoteMetrics instance.
     *
     * @param registry the Micrometer registry to use
     */
    public QuoteMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Initialize atomic values for gauges
        this.activeMintQuotes = new AtomicLong(0);
        this.activeMeltQuotes = new AtomicLong(0);

        // Register gauges for active quotes
        Gauge.builder(METRIC_PREFIX + "active", activeMintQuotes, AtomicLong::get)
                .description("Number of active mint quotes")
                .tag("type", "mint")
                .register(registry);

        Gauge.builder(METRIC_PREFIX + "active", activeMeltQuotes, AtomicLong::get)
                .description("Number of active melt quotes")
                .tag("type", "melt")
                .register(registry);

        // Amount counters
        this.mintQuoteAmountTotal = Counter.builder(METRIC_PREFIX + "amount_total")
                .description("Total amount requested in mint quotes")
                .tag("type", "mint")
                .tag("unit", "sat")
                .register(registry);

        this.meltQuoteAmountTotal = Counter.builder(METRIC_PREFIX + "amount_total")
                .description("Total amount requested in melt quotes")
                .tag("type", "melt")
                .tag("unit", "sat")
                .register(registry);

        this.mintQuoteCompletedAmountTotal = Counter.builder(METRIC_PREFIX + "completed_amount_total")
                .description("Total amount completed in mint quotes")
                .tag("type", "mint")
                .tag("unit", "sat")
                .register(registry);

        this.meltQuoteCompletedAmountTotal = Counter.builder(METRIC_PREFIX + "completed_amount_total")
                .description("Total amount completed in melt quotes")
                .tag("type", "melt")
                .tag("unit", "sat")
                .register(registry);

        log.debug("QuoteMetrics initialized");
    }

    /**
     * Records a mint quote being created.
     *
     * @param method the payment method (e.g., "bolt11")
     * @param amount the quote amount in sats
     */
    public void recordMintQuoteCreated(String method, long amount) {
        getCreatedCounter("mint", method).increment();
        mintQuoteAmountTotal.increment(amount);
        activeMintQuotes.incrementAndGet();
        log.trace("Recorded mint quote created: method={}, amount={}", method, amount);
    }

    /**
     * Records a melt quote being created.
     *
     * @param method the payment method (e.g., "bolt11")
     * @param amount the quote amount in sats
     */
    public void recordMeltQuoteCreated(String method, long amount) {
        getCreatedCounter("melt", method).increment();
        meltQuoteAmountTotal.increment(amount);
        activeMeltQuotes.incrementAndGet();
        log.trace("Recorded melt quote created: method={}, amount={}", method, amount);
    }

    /**
     * Records a mint quote being completed (paid and tokens minted).
     *
     * @param method the payment method
     * @param amount the completed amount in sats
     */
    public void recordMintQuoteCompleted(String method, long amount) {
        getCompletedCounter("mint", method).increment();
        mintQuoteCompletedAmountTotal.increment(amount);
        activeMintQuotes.decrementAndGet();
        log.trace("Recorded mint quote completed: method={}, amount={}", method, amount);
    }

    /**
     * Records a melt quote being completed (tokens melted and payment sent).
     *
     * @param method the payment method
     * @param amount the completed amount in sats
     */
    public void recordMeltQuoteCompleted(String method, long amount) {
        getCompletedCounter("melt", method).increment();
        meltQuoteCompletedAmountTotal.increment(amount);
        activeMeltQuotes.decrementAndGet();
        log.trace("Recorded melt quote completed: method={}, amount={}", method, amount);
    }

    /**
     * Records a quote expiring without completion.
     *
     * @param type the quote type ("mint" or "melt")
     * @param method the payment method
     */
    public void recordQuoteExpired(String type, String method) {
        getExpiredCounter(type, method).increment();
        if ("mint".equals(type)) {
            activeMintQuotes.decrementAndGet();
        } else if ("melt".equals(type)) {
            activeMeltQuotes.decrementAndGet();
        }
        log.trace("Recorded quote expired: type={}, method={}", type, method);
    }

    /**
     * Records a quote failing.
     *
     * @param type the quote type ("mint" or "melt")
     * @param method the payment method
     * @param reason the failure reason
     */
    public void recordQuoteFailed(String type, String method, String reason) {
        getFailedCounter(type, method, reason).increment();
        if ("mint".equals(type)) {
            activeMintQuotes.decrementAndGet();
        } else if ("melt".equals(type)) {
            activeMeltQuotes.decrementAndGet();
        }
        log.trace("Recorded quote failed: type={}, method={}, reason={}", type, method, reason);
    }

    /**
     * Records the processing time for a quote operation.
     *
     * @param type the quote type ("mint" or "melt")
     * @param method the payment method
     * @param durationNanos duration in nanoseconds
     */
    public void recordQuoteProcessingTime(String type, String method, long durationNanos) {
        getProcessingTimer(type, method).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Starts a timer sample for measuring quote processing.
     *
     * @return a new timer sample
     */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops a timer sample and records to the specified quote timer.
     *
     * @param sample the timer sample
     * @param type the quote type
     * @param method the payment method
     * @return the duration in nanoseconds
     */
    public long stopTimer(Timer.Sample sample, String type, String method) {
        return sample.stop(getProcessingTimer(type, method));
    }

    /**
     * Gets the current number of active mint quotes.
     *
     * @return active mint quote count
     */
    public long getActiveMintQuotes() {
        return activeMintQuotes.get();
    }

    /**
     * Gets the current number of active melt quotes.
     *
     * @return active melt quote count
     */
    public long getActiveMeltQuotes() {
        return activeMeltQuotes.get();
    }

    /**
     * Sets the active mint quote count (for initialization from DB).
     *
     * @param count the count
     */
    public void setActiveMintQuotes(long count) {
        activeMintQuotes.set(count);
    }

    /**
     * Sets the active melt quote count (for initialization from DB).
     *
     * @param count the count
     */
    public void setActiveMeltQuotes(long count) {
        activeMeltQuotes.set(count);
    }

    // Helper methods for per-type/method counters

    private Counter getCreatedCounter(String type, String method) {
        String key = type + "_" + method;
        return createdCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "created_total")
                        .description("Quotes created")
                        .tag("type", type)
                        .tag("method", normalizeMethod(method))
                        .register(registry));
    }

    private Counter getCompletedCounter(String type, String method) {
        String key = type + "_" + method;
        return completedCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "completed_total")
                        .description("Quotes completed")
                        .tag("type", type)
                        .tag("method", normalizeMethod(method))
                        .register(registry));
    }

    private Counter getExpiredCounter(String type, String method) {
        String key = type + "_" + method;
        return expiredCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "expired_total")
                        .description("Quotes expired")
                        .tag("type", type)
                        .tag("method", normalizeMethod(method))
                        .register(registry));
    }

    private Counter getFailedCounter(String type, String method, String reason) {
        String key = type + "_" + method + "_" + reason;
        return failedCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "failed_total")
                        .description("Quotes failed")
                        .tag("type", type)
                        .tag("method", normalizeMethod(method))
                        .tag("reason", reason)
                        .register(registry));
    }

    private Timer getProcessingTimer(String type, String method) {
        String key = type + "_" + method;
        return processingTimers.computeIfAbsent(key, k ->
                Timer.builder(METRIC_PREFIX + "processing_duration_seconds")
                        .description("Quote processing duration")
                        .tag("type", type)
                        .tag("method", normalizeMethod(method))
                        .register(registry));
    }

    private String normalizeMethod(String method) {
        return method != null ? method.toLowerCase() : "unknown";
    }
}
