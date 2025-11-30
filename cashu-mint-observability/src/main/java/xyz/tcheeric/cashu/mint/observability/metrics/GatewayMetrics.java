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
 * Metrics for Lightning gateway operations.
 *
 * <p>This class provides metrics instrumentation for:
 * <ul>
 *   <li>Payment operations (send/receive) with success/failure tracking</li>
 *   <li>Invoice generation and payment verification</li>
 *   <li>Gateway-specific routing fees</li>
 *   <li>Payment timing and latency</li>
 *   <li>Gateway health status</li>
 * </ul>
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_gateway_*}
 */
@Slf4j
public class GatewayMetrics {

    private static final String METRIC_PREFIX = "cashu_mint_gateway_";

    private final MeterRegistry registry;

    // Atomic counters for gauges
    private final AtomicLong pendingPayments;
    private final AtomicLong healthStatus;

    // Payment counters by gateway type
    private final ConcurrentHashMap<String, Counter> paymentsSentCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> paymentsReceivedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> paymentFailuresCounters = new ConcurrentHashMap<>();

    // Amount counters
    private final ConcurrentHashMap<String, Counter> amountSentCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> amountReceivedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> routingFeesCounters = new ConcurrentHashMap<>();

    // Invoice counters
    private final Counter invoicesCreatedTotal;
    private final Counter invoicesPaidTotal;
    private final Counter invoicesExpiredTotal;

    // Timers by gateway type
    private final ConcurrentHashMap<String, Timer> paymentSendTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> paymentReceiveTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> invoiceCreationTimers = new ConcurrentHashMap<>();

    /**
     * Creates a new GatewayMetrics instance.
     *
     * @param registry the Micrometer registry to use
     */
    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Initialize atomic values for gauges
        this.pendingPayments = new AtomicLong(0);
        this.healthStatus = new AtomicLong(1);  // 1 = healthy, 0 = unhealthy

        // Register gauges
        Gauge.builder(METRIC_PREFIX + "pending_payments", pendingPayments, AtomicLong::get)
                .description("Number of pending gateway payments")
                .register(registry);

        Gauge.builder(METRIC_PREFIX + "health", healthStatus, AtomicLong::get)
                .description("Gateway health status (1=healthy, 0=unhealthy)")
                .register(registry);

        // Invoice counters
        this.invoicesCreatedTotal = Counter.builder(METRIC_PREFIX + "invoices_created_total")
                .description("Total invoices created")
                .register(registry);

        this.invoicesPaidTotal = Counter.builder(METRIC_PREFIX + "invoices_paid_total")
                .description("Total invoices paid")
                .register(registry);

        this.invoicesExpiredTotal = Counter.builder(METRIC_PREFIX + "invoices_expired_total")
                .description("Total invoices expired")
                .register(registry);

        log.debug("GatewayMetrics initialized");
    }

    /**
     * Records a payment being sent through the gateway.
     *
     * @param gatewayType the gateway type (e.g., "bolt11", "phoenixd")
     * @param amountSats the amount sent in sats
     * @param routingFeeSats the routing fee paid in sats
     */
    public void recordPaymentSent(String gatewayType, long amountSats, long routingFeeSats) {
        getPaymentsSentCounter(gatewayType).increment();
        getAmountSentCounter(gatewayType).increment(amountSats);
        getRoutingFeesCounter(gatewayType).increment(routingFeeSats);
        pendingPayments.decrementAndGet();
        log.trace("Recorded payment sent: gateway={}, amount={}, fee={}", gatewayType, amountSats, routingFeeSats);
    }

    /**
     * Records a payment being received through the gateway.
     *
     * @param gatewayType the gateway type (e.g., "bolt11", "phoenixd")
     * @param amountSats the amount received in sats
     */
    public void recordPaymentReceived(String gatewayType, long amountSats) {
        getPaymentsReceivedCounter(gatewayType).increment();
        getAmountReceivedCounter(gatewayType).increment(amountSats);
        log.trace("Recorded payment received: gateway={}, amount={}", gatewayType, amountSats);
    }

    /**
     * Records a payment failure.
     *
     * @param gatewayType the gateway type (e.g., "bolt11", "phoenixd")
     * @param errorType the error type (e.g., "timeout", "no_route", "insufficient_funds")
     */
    public void recordPaymentFailure(String gatewayType, String errorType) {
        getPaymentFailuresCounter(gatewayType, errorType).increment();
        pendingPayments.decrementAndGet();
        log.trace("Recorded payment failure: gateway={}, error={}", gatewayType, errorType);
    }

    /**
     * Records a payment being initiated (pending).
     *
     * @param gatewayType the gateway type
     */
    public void recordPaymentInitiated(String gatewayType) {
        pendingPayments.incrementAndGet();
        log.trace("Recorded payment initiated: gateway={}", gatewayType);
    }

    /**
     * Records an invoice being created.
     */
    public void recordInvoiceCreated() {
        invoicesCreatedTotal.increment();
        log.trace("Recorded invoice created");
    }

    /**
     * Records an invoice being paid.
     */
    public void recordInvoicePaid() {
        invoicesPaidTotal.increment();
        log.trace("Recorded invoice paid");
    }

    /**
     * Records an invoice expiring.
     */
    public void recordInvoiceExpired() {
        invoicesExpiredTotal.increment();
        log.trace("Recorded invoice expired");
    }

    /**
     * Records the time taken to send a payment.
     *
     * @param gatewayType the gateway type
     * @param durationNanos duration in nanoseconds
     */
    public void recordPaymentSendTime(String gatewayType, long durationNanos) {
        getPaymentSendTimer(gatewayType).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records the time taken to receive a payment (from invoice creation to paid).
     *
     * @param gatewayType the gateway type
     * @param durationNanos duration in nanoseconds
     */
    public void recordPaymentReceiveTime(String gatewayType, long durationNanos) {
        getPaymentReceiveTimer(gatewayType).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records the time taken to create an invoice.
     *
     * @param gatewayType the gateway type
     * @param durationNanos duration in nanoseconds
     */
    public void recordInvoiceCreationTime(String gatewayType, long durationNanos) {
        getInvoiceCreationTimer(gatewayType).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Starts a timer sample for measuring gateway operations.
     *
     * @return a new timer sample
     */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops a timer sample and records to the payment send timer.
     *
     * @param sample the timer sample
     * @param gatewayType the gateway type
     * @return the duration in nanoseconds
     */
    public long stopPaymentSendTimer(Timer.Sample sample, String gatewayType) {
        return sample.stop(getPaymentSendTimer(gatewayType));
    }

    /**
     * Stops a timer sample and records to the payment receive timer.
     *
     * @param sample the timer sample
     * @param gatewayType the gateway type
     * @return the duration in nanoseconds
     */
    public long stopPaymentReceiveTimer(Timer.Sample sample, String gatewayType) {
        return sample.stop(getPaymentReceiveTimer(gatewayType));
    }

    /**
     * Stops a timer sample and records to the invoice creation timer.
     *
     * @param sample the timer sample
     * @param gatewayType the gateway type
     * @return the duration in nanoseconds
     */
    public long stopInvoiceCreationTimer(Timer.Sample sample, String gatewayType) {
        return sample.stop(getInvoiceCreationTimer(gatewayType));
    }

    /**
     * Marks the gateway as healthy.
     */
    public void markHealthy() {
        healthStatus.set(1);
        log.trace("Gateway marked healthy");
    }

    /**
     * Marks the gateway as unhealthy.
     */
    public void markUnhealthy() {
        healthStatus.set(0);
        log.trace("Gateway marked unhealthy");
    }

    /**
     * Gets the current number of pending payments.
     *
     * @return pending payment count
     */
    public long getPendingPayments() {
        return pendingPayments.get();
    }

    /**
     * Gets the current health status.
     *
     * @return 1 if healthy, 0 if unhealthy
     */
    public long getHealthStatus() {
        return healthStatus.get();
    }

    /**
     * Sets the pending payments count (for initialization from DB).
     *
     * @param count the count
     */
    public void setPendingPayments(long count) {
        pendingPayments.set(count);
    }

    // Helper methods for creating metrics by gateway type

    private Counter getPaymentsSentCounter(String gatewayType) {
        return paymentsSentCounters.computeIfAbsent(gatewayType, gt ->
                Counter.builder(METRIC_PREFIX + "payments_sent_total")
                        .description("Total payments sent through gateway")
                        .tag("gateway", gt)
                        .register(registry));
    }

    private Counter getPaymentsReceivedCounter(String gatewayType) {
        return paymentsReceivedCounters.computeIfAbsent(gatewayType, gt ->
                Counter.builder(METRIC_PREFIX + "payments_received_total")
                        .description("Total payments received through gateway")
                        .tag("gateway", gt)
                        .register(registry));
    }

    private Counter getPaymentFailuresCounter(String gatewayType, String errorType) {
        String key = gatewayType + ":" + errorType;
        return paymentFailuresCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "payment_failures_total")
                        .description("Total payment failures")
                        .tag("gateway", gatewayType)
                        .tag("error_type", errorType)
                        .register(registry));
    }

    private Counter getAmountSentCounter(String gatewayType) {
        return amountSentCounters.computeIfAbsent(gatewayType, gt ->
                Counter.builder(METRIC_PREFIX + "amount_sent_total")
                        .description("Total amount sent through gateway in sats")
                        .tag("gateway", gt)
                        .tag("unit", "sat")
                        .register(registry));
    }

    private Counter getAmountReceivedCounter(String gatewayType) {
        return amountReceivedCounters.computeIfAbsent(gatewayType, gt ->
                Counter.builder(METRIC_PREFIX + "amount_received_total")
                        .description("Total amount received through gateway in sats")
                        .tag("gateway", gt)
                        .tag("unit", "sat")
                        .register(registry));
    }

    private Counter getRoutingFeesCounter(String gatewayType) {
        return routingFeesCounters.computeIfAbsent(gatewayType, gt ->
                Counter.builder(METRIC_PREFIX + "routing_fees_total")
                        .description("Total routing fees paid in sats")
                        .tag("gateway", gt)
                        .tag("unit", "sat")
                        .register(registry));
    }

    private Timer getPaymentSendTimer(String gatewayType) {
        return paymentSendTimers.computeIfAbsent(gatewayType, gt ->
                Timer.builder(METRIC_PREFIX + "payment_send_duration_seconds")
                        .description("Time to send payment through gateway")
                        .tag("gateway", gt)
                        .register(registry));
    }

    private Timer getPaymentReceiveTimer(String gatewayType) {
        return paymentReceiveTimers.computeIfAbsent(gatewayType, gt ->
                Timer.builder(METRIC_PREFIX + "payment_receive_duration_seconds")
                        .description("Time from invoice creation to payment received")
                        .tag("gateway", gt)
                        .register(registry));
    }

    private Timer getInvoiceCreationTimer(String gatewayType) {
        return invoiceCreationTimers.computeIfAbsent(gatewayType, gt ->
                Timer.builder(METRIC_PREFIX + "invoice_creation_duration_seconds")
                        .description("Time to create an invoice")
                        .tag("gateway", gt)
                        .register(registry));
    }
}
