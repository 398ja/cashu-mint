package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GatewayMetrics}.
 *
 * Verifies that gateway metrics are correctly recorded.
 */
class GatewayMetricsTest {

    private SimpleMeterRegistry registry;
    private GatewayMetrics gatewayMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        gatewayMetrics = new GatewayMetrics(registry);
    }

    @Test
    void recordPaymentInitiated_incrementsPendingGauge() {
        // When a payment is initiated
        gatewayMetrics.recordPaymentInitiated("bolt11");

        // Then the pending gauge is incremented
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(1);
    }

    @Test
    void recordPaymentSent_decrementsPendingAndIncrementsSentCounter() {
        // Given a pending payment
        gatewayMetrics.recordPaymentInitiated("bolt11");

        // When the payment is sent
        gatewayMetrics.recordPaymentSent("bolt11", 1000, 10);

        // Then the pending gauge is decremented
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(0);

        // And the sent counter is incremented with gateway tag
        Counter counter = registry.find("cashu_mint_gateway_payments_sent_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the amount sent counter reflects the amount
        Counter amountCounter = registry.find("cashu_mint_gateway_amount_sent_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(amountCounter).isNotNull();
        assertThat(amountCounter.count()).isEqualTo(1000.0);

        // And the routing fees counter reflects the fee
        Counter feeCounter = registry.find("cashu_mint_gateway_routing_fees_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(feeCounter).isNotNull();
        assertThat(feeCounter.count()).isEqualTo(10.0);
    }

    @Test
    void recordPaymentReceived_incrementsReceivedCounter() {
        // When a payment is received
        gatewayMetrics.recordPaymentReceived("bolt11", 500);

        // Then the received counter is incremented
        Counter counter = registry.find("cashu_mint_gateway_payments_received_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the amount received counter reflects the amount
        Counter amountCounter = registry.find("cashu_mint_gateway_amount_received_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(amountCounter).isNotNull();
        assertThat(amountCounter.count()).isEqualTo(500.0);
    }

    @Test
    void recordPaymentFailure_incrementsFailureCounterWithTags() {
        // Given a pending payment
        gatewayMetrics.recordPaymentInitiated("phoenixd");

        // When the payment fails
        gatewayMetrics.recordPaymentFailure("phoenixd", "no_route");

        // Then the pending gauge is decremented
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(0);

        // And the failure counter is incremented with gateway and error_type tags
        Counter counter = registry.find("cashu_mint_gateway_payment_failures_total")
                .tag("gateway", "phoenixd")
                .tag("error_type", "no_route")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordPaymentFailure_differentErrors_trackedSeparately() {
        // When payments fail for different reasons
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentFailure("bolt11", "timeout");
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentFailure("bolt11", "no_route");
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentFailure("bolt11", "timeout");

        // Then each error type is tracked separately
        Counter timeoutCounter = registry.find("cashu_mint_gateway_payment_failures_total")
                .tag("gateway", "bolt11")
                .tag("error_type", "timeout")
                .counter();
        assertThat(timeoutCounter.count()).isEqualTo(2.0);

        Counter noRouteCounter = registry.find("cashu_mint_gateway_payment_failures_total")
                .tag("gateway", "bolt11")
                .tag("error_type", "no_route")
                .counter();
        assertThat(noRouteCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordInvoiceCreated_incrementsCounter() {
        // When an invoice is created
        gatewayMetrics.recordInvoiceCreated();

        // Then the counter is incremented
        Counter counter = registry.find("cashu_mint_gateway_invoices_created_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordInvoicePaid_incrementsCounter() {
        // When an invoice is paid
        gatewayMetrics.recordInvoicePaid();

        // Then the counter is incremented
        Counter counter = registry.find("cashu_mint_gateway_invoices_paid_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordInvoiceExpired_incrementsCounter() {
        // When an invoice expires
        gatewayMetrics.recordInvoiceExpired();

        // Then the counter is incremented
        Counter counter = registry.find("cashu_mint_gateway_invoices_expired_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordPaymentSendTime_recordsTimer() {
        // When recording payment send time
        gatewayMetrics.recordPaymentSendTime("bolt11", TimeUnit.MILLISECONDS.toNanos(100));
        gatewayMetrics.recordPaymentSendTime("bolt11", TimeUnit.MILLISECONDS.toNanos(200));

        // Then the timer captures the durations
        Timer timer = registry.find("cashu_mint_gateway_payment_send_duration_seconds")
                .tag("gateway", "bolt11")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(300.0);
    }

    @Test
    void recordPaymentReceiveTime_recordsTimer() {
        // When recording payment receive time
        gatewayMetrics.recordPaymentReceiveTime("phoenixd", TimeUnit.MILLISECONDS.toNanos(5000));

        // Then the timer captures the duration
        Timer timer = registry.find("cashu_mint_gateway_payment_receive_duration_seconds")
                .tag("gateway", "phoenixd")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5000.0);
    }

    @Test
    void recordInvoiceCreationTime_recordsTimer() {
        // When recording invoice creation time
        gatewayMetrics.recordInvoiceCreationTime("bolt11", TimeUnit.MILLISECONDS.toNanos(50));

        // Then the timer captures the duration
        Timer timer = registry.find("cashu_mint_gateway_invoice_creation_duration_seconds")
                .tag("gateway", "bolt11")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50.0);
    }

    @Test
    void timerSample_measuresPaymentSendTime() {
        // When using timer samples for payment send
        Timer.Sample sample = gatewayMetrics.startTimer();

        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = gatewayMetrics.stopPaymentSendTimer(sample, "bolt11");

        // Then the duration is recorded
        assertThat(duration).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(10));

        Timer timer = registry.find("cashu_mint_gateway_payment_send_duration_seconds")
                .tag("gateway", "bolt11")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void timerSample_measuresPaymentReceiveTime() {
        // When using timer samples for payment receive
        Timer.Sample sample = gatewayMetrics.startTimer();

        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = gatewayMetrics.stopPaymentReceiveTimer(sample, "phoenixd");

        // Then the duration is recorded
        assertThat(duration).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(10));

        Timer timer = registry.find("cashu_mint_gateway_payment_receive_duration_seconds")
                .tag("gateway", "phoenixd")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void timerSample_measuresInvoiceCreationTime() {
        // When using timer samples for invoice creation
        Timer.Sample sample = gatewayMetrics.startTimer();

        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = gatewayMetrics.stopInvoiceCreationTimer(sample, "bolt11");

        // Then the duration is recorded
        assertThat(duration).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(10));

        Timer timer = registry.find("cashu_mint_gateway_invoice_creation_duration_seconds")
                .tag("gateway", "bolt11")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void healthStatus_markHealthy() {
        // When marking gateway as healthy
        gatewayMetrics.markHealthy();

        // Then health status is 1
        assertThat(gatewayMetrics.getHealthStatus()).isEqualTo(1);

        // And gauge reflects the status
        Gauge gauge = registry.find("cashu_mint_gateway_health")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void healthStatus_markUnhealthy() {
        // When marking gateway as unhealthy
        gatewayMetrics.markUnhealthy();

        // Then health status is 0
        assertThat(gatewayMetrics.getHealthStatus()).isEqualTo(0);

        // And gauge reflects the status
        Gauge gauge = registry.find("cashu_mint_gateway_health")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void multipleGatewayTypes_trackedSeparately() {
        // Given payments through different gateway types
        gatewayMetrics.recordPaymentReceived("bolt11", 1000);
        gatewayMetrics.recordPaymentReceived("bolt11", 2000);
        gatewayMetrics.recordPaymentReceived("phoenixd", 500);

        // Then each gateway type is tracked separately
        Counter bolt11Counter = registry.find("cashu_mint_gateway_payments_received_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(bolt11Counter.count()).isEqualTo(2.0);

        Counter phoenixdCounter = registry.find("cashu_mint_gateway_payments_received_total")
                .tag("gateway", "phoenixd")
                .counter();
        assertThat(phoenixdCounter.count()).isEqualTo(1.0);

        // And amounts are tracked per gateway
        Counter bolt11Amount = registry.find("cashu_mint_gateway_amount_received_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(bolt11Amount.count()).isEqualTo(3000.0);

        Counter phoenixdAmount = registry.find("cashu_mint_gateway_amount_received_total")
                .tag("gateway", "phoenixd")
                .counter();
        assertThat(phoenixdAmount.count()).isEqualTo(500.0);
    }

    @Test
    void pendingPayments_tracksPendingState() {
        // Given multiple pending payments
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentInitiated("phoenixd");

        // Then pending count is correct
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(3);

        // When some payments complete
        gatewayMetrics.recordPaymentSent("bolt11", 100, 1);
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(2);

        // When a payment fails
        gatewayMetrics.recordPaymentFailure("bolt11", "timeout");
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(1);
    }

    @Test
    void setPendingPayments_initializesFromDb() {
        // When setting pending payments from DB state
        gatewayMetrics.setPendingPayments(10);

        // Then gauge reflects the value
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(10);

        // And subsequent operations adjust correctly
        gatewayMetrics.recordPaymentSent("bolt11", 100, 1);
        assertThat(gatewayMetrics.getPendingPayments()).isEqualTo(9);
    }

    @Test
    void routingFeesAccumulation_tracksAcrossMultiplePayments() {
        // Given multiple payments with routing fees
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentSent("bolt11", 1000, 10);
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentSent("bolt11", 2000, 25);
        gatewayMetrics.recordPaymentInitiated("bolt11");
        gatewayMetrics.recordPaymentSent("bolt11", 500, 5);

        // Then routing fees are accumulated
        Counter feeCounter = registry.find("cashu_mint_gateway_routing_fees_total")
                .tag("gateway", "bolt11")
                .counter();
        assertThat(feeCounter).isNotNull();
        assertThat(feeCounter.count()).isEqualTo(40.0);
    }

    @Test
    void gaugeRegistration_createsGaugesWithCorrectDescriptions() {
        // When GatewayMetrics is created

        // Then pending payments gauge is registered
        Gauge pendingGauge = registry.find("cashu_mint_gateway_pending_payments")
                .gauge();
        assertThat(pendingGauge).isNotNull();

        // And health gauge is registered
        Gauge healthGauge = registry.find("cashu_mint_gateway_health")
                .gauge();
        assertThat(healthGauge).isNotNull();
    }

    @Test
    void invoiceLifecycle_tracksCreatePayExpire() {
        // Given multiple invoices
        gatewayMetrics.recordInvoiceCreated();
        gatewayMetrics.recordInvoiceCreated();
        gatewayMetrics.recordInvoiceCreated();

        // When some are paid and some expire
        gatewayMetrics.recordInvoicePaid();
        gatewayMetrics.recordInvoicePaid();
        gatewayMetrics.recordInvoiceExpired();

        // Then counters reflect the state
        Counter createdCounter = registry.find("cashu_mint_gateway_invoices_created_total")
                .counter();
        assertThat(createdCounter.count()).isEqualTo(3.0);

        Counter paidCounter = registry.find("cashu_mint_gateway_invoices_paid_total")
                .counter();
        assertThat(paidCounter.count()).isEqualTo(2.0);

        Counter expiredCounter = registry.find("cashu_mint_gateway_invoices_expired_total")
                .counter();
        assertThat(expiredCounter.count()).isEqualTo(1.0);
    }
}
