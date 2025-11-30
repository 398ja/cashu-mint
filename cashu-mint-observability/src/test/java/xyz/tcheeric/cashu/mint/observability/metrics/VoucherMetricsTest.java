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
 * Unit tests for {@link VoucherMetrics}.
 *
 * Verifies that voucher metrics are correctly recorded.
 */
class VoucherMetricsTest {

    private SimpleMeterRegistry registry;
    private VoucherMetrics voucherMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        voucherMetrics = new VoucherMetrics(registry);
    }

    @Test
    void recordVoucherQuoteCreated_incrementsActiveGaugeAndFees() {
        // When a voucher quote is created
        voucherMetrics.recordVoucherQuoteCreated(1000, 50);

        // Then the active gauge is incremented
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(1);

        // And the fees counter reflects the fee amount
        assertThat(voucherMetrics.getFeesCollected()).isEqualTo(50.0);
    }

    @Test
    void recordVoucherIssued_incrementsCountersAndDecrementsGauge() {
        // Given an active voucher quote
        voucherMetrics.recordVoucherQuoteCreated(1000, 50);

        // When the voucher is issued
        voucherMetrics.recordVoucherIssued(1000);

        // Then the issued counter is incremented
        Counter counter = registry.find("cashu_mint_vouchers_issued_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the face value counter reflects the amount
        Counter faceValueCounter = registry.find("cashu_mint_vouchers_face_value_issued_total")
                .tag("unit", "sat")
                .counter();
        assertThat(faceValueCounter).isNotNull();
        assertThat(faceValueCounter.count()).isEqualTo(1000.0);

        // And the active gauge is decremented
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(0);
    }

    @Test
    void recordVoucherRedeemed_incrementsCounters() {
        // When a voucher is redeemed
        voucherMetrics.recordVoucherRedeemed(500);

        // Then the redeemed counter is incremented
        Counter counter = registry.find("cashu_mint_vouchers_redeemed_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the face value counter reflects the amount
        Counter faceValueCounter = registry.find("cashu_mint_vouchers_face_value_redeemed_total")
                .tag("unit", "sat")
                .counter();
        assertThat(faceValueCounter).isNotNull();
        assertThat(faceValueCounter.count()).isEqualTo(500.0);
    }

    @Test
    void recordVoucherRejected_incrementsCounterWithReason() {
        // When a voucher is rejected
        voucherMetrics.recordVoucherRejected("expired");

        // Then the rejected counter is incremented with reason tag
        Counter counter = registry.find("cashu_mint_vouchers_rejected_total")
                .tag("reason", "expired")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordVoucherRejected_differentReasons_trackedSeparately() {
        // When vouchers are rejected for different reasons
        voucherMetrics.recordVoucherRejected("expired");
        voucherMetrics.recordVoucherRejected("invalid_signature");
        voucherMetrics.recordVoucherRejected("already_redeemed");
        voucherMetrics.recordVoucherRejected("expired");

        // Then each reason is tracked separately
        Counter expiredCounter = registry.find("cashu_mint_vouchers_rejected_total")
                .tag("reason", "expired")
                .counter();
        assertThat(expiredCounter.count()).isEqualTo(2.0);

        Counter invalidSigCounter = registry.find("cashu_mint_vouchers_rejected_total")
                .tag("reason", "invalid_signature")
                .counter();
        assertThat(invalidSigCounter.count()).isEqualTo(1.0);

        Counter alreadyRedeemedCounter = registry.find("cashu_mint_vouchers_rejected_total")
                .tag("reason", "already_redeemed")
                .counter();
        assertThat(alreadyRedeemedCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordVoucherQuoteExpired_decrementsGaugeAndTracksRejection() {
        // Given an active voucher quote
        voucherMetrics.recordVoucherQuoteCreated(1000, 50);
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(1);

        // When the quote expires
        voucherMetrics.recordVoucherQuoteExpired();

        // Then the active gauge is decremented
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(0);

        // And it's tracked as a rejection with reason
        Counter counter = registry.find("cashu_mint_vouchers_rejected_total")
                .tag("reason", "quote_expired")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordVoucherQuoteTime_recordsTimer() {
        // When recording quote processing time
        voucherMetrics.recordVoucherQuoteTime(TimeUnit.MILLISECONDS.toNanos(100));
        voucherMetrics.recordVoucherQuoteTime(TimeUnit.MILLISECONDS.toNanos(200));

        // Then the timer captures the durations
        Timer timer = registry.find("cashu_mint_vouchers_quote_duration_seconds")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(300.0);
    }

    @Test
    void recordVoucherRedemptionTime_recordsTimer() {
        // When recording redemption processing time
        voucherMetrics.recordVoucherRedemptionTime(TimeUnit.MILLISECONDS.toNanos(150));
        voucherMetrics.recordVoucherRedemptionTime(TimeUnit.MILLISECONDS.toNanos(250));

        // Then the timer captures the durations
        Timer timer = registry.find("cashu_mint_vouchers_redemption_duration_seconds")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(400.0);
    }

    @Test
    void timerSample_measuresQuoteProcessing() {
        // When using timer samples for quote
        Timer.Sample sample = voucherMetrics.startTimer();

        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = voucherMetrics.stopQuoteTimer(sample);

        // Then the duration is recorded
        assertThat(duration).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(10));

        Timer timer = registry.find("cashu_mint_vouchers_quote_duration_seconds")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void timerSample_measuresRedemptionProcessing() {
        // When using timer samples for redemption
        Timer.Sample sample = voucherMetrics.startTimer();

        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = voucherMetrics.stopRedemptionTimer(sample);

        // Then the duration is recorded
        assertThat(duration).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(10));

        Timer timer = registry.find("cashu_mint_vouchers_redemption_duration_seconds")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void multipleQuotes_tracksActiveCountCorrectly() {
        // Given multiple voucher quotes
        voucherMetrics.recordVoucherQuoteCreated(100, 5);
        voucherMetrics.recordVoucherQuoteCreated(200, 10);
        voucherMetrics.recordVoucherQuoteCreated(300, 15);

        // Then active count is correct
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(3);

        // And total fees are accumulated
        assertThat(voucherMetrics.getFeesCollected()).isEqualTo(30.0);

        // When one is issued
        voucherMetrics.recordVoucherIssued(100);
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(2);

        // When one expires
        voucherMetrics.recordVoucherQuoteExpired();
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(1);
    }

    @Test
    void setActiveVoucherQuotes_initializesFromDb() {
        // When setting active quotes from DB state
        voucherMetrics.setActiveVoucherQuotes(5);

        // Then gauge reflects the value
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(5);

        // And subsequent operations adjust correctly
        voucherMetrics.recordVoucherIssued(100);
        assertThat(voucherMetrics.getActiveVoucherQuotes()).isEqualTo(4);
    }

    @Test
    void getVouchersIssued_returnsCorrectCount() {
        // When issuing multiple vouchers
        voucherMetrics.recordVoucherQuoteCreated(100, 5);
        voucherMetrics.recordVoucherQuoteCreated(200, 10);
        voucherMetrics.recordVoucherIssued(100);
        voucherMetrics.recordVoucherIssued(200);

        // Then count is correct
        assertThat(voucherMetrics.getVouchersIssued()).isEqualTo(2.0);
    }

    @Test
    void getVouchersRedeemed_returnsCorrectCount() {
        // When redeeming multiple vouchers
        voucherMetrics.recordVoucherRedeemed(100);
        voucherMetrics.recordVoucherRedeemed(200);
        voucherMetrics.recordVoucherRedeemed(300);

        // Then count is correct
        assertThat(voucherMetrics.getVouchersRedeemed()).isEqualTo(3.0);
    }

    @Test
    void gaugeRegistration_createsGaugeWithCorrectDescription() {
        // When VoucherMetrics is created

        // Then the active quotes gauge is registered
        Gauge gauge = registry.find("cashu_mint_vouchers_quotes_active")
                .gauge();
        assertThat(gauge).isNotNull();
    }

    @Test
    void feeAccumulation_tracksAcrossMultipleQuotes() {
        // Given quotes with various fee amounts
        voucherMetrics.recordVoucherQuoteCreated(1000, 100);
        voucherMetrics.recordVoucherQuoteCreated(2000, 200);
        voucherMetrics.recordVoucherQuoteCreated(500, 50);

        // Then total fees are accumulated correctly
        assertThat(voucherMetrics.getFeesCollected()).isEqualTo(350.0);

        // And counter has correct value
        Counter feeCounter = registry.find("cashu_mint_vouchers_fees_collected_total")
                .tag("unit", "sat")
                .counter();
        assertThat(feeCounter).isNotNull();
        assertThat(feeCounter.count()).isEqualTo(350.0);
    }

    @Test
    void faceValueTracking_tracksIssuedAndRedeemedSeparately() {
        // Given vouchers being issued and redeemed
        voucherMetrics.recordVoucherQuoteCreated(1000, 50);
        voucherMetrics.recordVoucherIssued(1000);
        voucherMetrics.recordVoucherQuoteCreated(2000, 100);
        voucherMetrics.recordVoucherIssued(2000);

        voucherMetrics.recordVoucherRedeemed(500);
        voucherMetrics.recordVoucherRedeemed(1500);

        // Then issued face value is correct
        Counter issuedCounter = registry.find("cashu_mint_vouchers_face_value_issued_total")
                .counter();
        assertThat(issuedCounter.count()).isEqualTo(3000.0);

        // And redeemed face value is correct
        Counter redeemedCounter = registry.find("cashu_mint_vouchers_face_value_redeemed_total")
                .counter();
        assertThat(redeemedCounter.count()).isEqualTo(2000.0);
    }
}
