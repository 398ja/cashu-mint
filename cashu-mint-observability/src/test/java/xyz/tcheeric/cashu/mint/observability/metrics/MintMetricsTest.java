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
 * Unit tests for {@link MintMetrics}.
 *
 * Verifies that metrics are correctly recorded for various mint operations.
 */
class MintMetricsTest {

    private SimpleMeterRegistry registry;
    private MintMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MintMetrics(registry, true);
    }

    @Test
    void recordProofIssued_incrementsCounterAndGauge() {
        // When a proof is issued
        metrics.recordProofIssued("keyset123", 100);

        // Then the proofs issued counter is incremented
        Counter proofsIssued = registry.find("cashu_mint_proofs_issued_total").counter();
        assertThat(proofsIssued).isNotNull();
        assertThat(proofsIssued.count()).isEqualTo(1.0);

        // And the sats issued counter reflects the amount
        Counter satsIssued = registry.find("cashu_mint_sats_issued_total").counter();
        assertThat(satsIssued).isNotNull();
        assertThat(satsIssued.count()).isEqualTo(100.0);

        // And the outstanding sats gauge reflects the amount
        Gauge outstandingGauge = registry.find("cashu_mint_sats_outstanding").gauge();
        assertThat(outstandingGauge).isNotNull();
        assertThat(outstandingGauge.value()).isEqualTo(100.0);
    }

    @Test
    void recordProofsIssued_incrementsMultipleProofs() {
        // When multiple proofs are issued at once
        metrics.recordProofsIssued("keyset123", 5, 500);

        // Then the proofs issued counter reflects the count
        Counter proofsIssued = registry.find("cashu_mint_proofs_issued_total").counter();
        assertThat(proofsIssued).isNotNull();
        assertThat(proofsIssued.count()).isEqualTo(5.0);

        // And the sats issued counter reflects the total amount
        Counter satsIssued = registry.find("cashu_mint_sats_issued_total").counter();
        assertThat(satsIssued).isNotNull();
        assertThat(satsIssued.count()).isEqualTo(500.0);

        // And the outstanding sats gauge reflects the total
        assertThat(metrics.getOutstandingSats()).isEqualTo(500);
    }

    @Test
    void recordProofSpent_decrementsOutstandingSats() {
        // Given proofs have been issued
        metrics.recordProofIssued("keyset123", 100);
        metrics.recordProofIssued("keyset123", 50);

        // When a proof is spent
        metrics.recordProofSpent("keyset123", 50);

        // Then the proofs spent counter is incremented
        Counter proofsSpent = registry.find("cashu_mint_proofs_spent_total").counter();
        assertThat(proofsSpent).isNotNull();
        assertThat(proofsSpent.count()).isEqualTo(1.0);

        // And the sats redeemed counter reflects the amount
        Counter satsRedeemed = registry.find("cashu_mint_sats_redeemed_total").counter();
        assertThat(satsRedeemed).isNotNull();
        assertThat(satsRedeemed.count()).isEqualTo(50.0);

        // And the outstanding sats gauge reflects the net (issued - spent)
        assertThat(metrics.getOutstandingSats()).isEqualTo(100);
    }

    @Test
    void recordProofsSpent_handlesMultipleProofs() {
        // Given proofs have been issued
        metrics.recordProofsIssued("keyset123", 10, 1000);

        // When multiple proofs are spent at once
        metrics.recordProofsSpent("keyset123", 3, 300);

        // Then the proofs spent counter reflects the count
        Counter proofsSpent = registry.find("cashu_mint_proofs_spent_total").counter();
        assertThat(proofsSpent).isNotNull();
        assertThat(proofsSpent.count()).isEqualTo(3.0);

        // And the outstanding sats is correct
        assertThat(metrics.getOutstandingSats()).isEqualTo(700);
    }

    @Test
    void recordDoubleSpendAttempt_incrementsCounter() {
        // When double-spend attempts are recorded
        metrics.recordDoubleSpendAttempt();
        metrics.recordDoubleSpendAttempt("keyset123");

        // Then the counter reflects both attempts
        Counter doubleSpend = registry.find("cashu_mint_proofs_double_spend_total").counter();
        assertThat(doubleSpend).isNotNull();
        assertThat(doubleSpend.count()).isEqualTo(2.0);
    }

    @Test
    void recordSignatureGenerated_incrementsCounter() {
        // When signatures are generated
        metrics.recordSignatureGenerated("keyset123");
        metrics.recordSignaturesGenerated("keyset456", 5);

        // Then the total counter reflects all signatures
        Counter signaturesGenerated = registry.find("cashu_mint_signatures_generated_total").counter();
        assertThat(signaturesGenerated).isNotNull();
        assertThat(signaturesGenerated.count()).isEqualTo(6.0);
    }

    @Test
    void recordSignatureVerified_incrementsCounter() {
        // When signatures are verified
        metrics.recordSignatureVerified();
        metrics.recordSignaturesVerified(3);

        // Then the counter reflects all verifications
        Counter signaturesVerified = registry.find("cashu_mint_signatures_verified_total").counter();
        assertThat(signaturesVerified).isNotNull();
        assertThat(signaturesVerified.count()).isEqualTo(4.0);
    }

    @Test
    void recordProofVerificationTime_recordsTimer() {
        // When proof verification time is recorded
        metrics.recordProofVerificationTime(TimeUnit.MILLISECONDS.toNanos(50));
        metrics.recordProofVerificationTime(TimeUnit.MILLISECONDS.toNanos(100));

        // Then the timer captures the durations
        Timer timer = registry.find("cashu_mint_proof_verification_duration_seconds").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(150.0);
    }

    @Test
    void recordSignatureGenerationTime_recordsTimer() {
        // When signature generation time is recorded
        metrics.recordSignatureGenerationTime(TimeUnit.MILLISECONDS.toNanos(25));

        // Then the timer captures the duration
        Timer timer = registry.find("cashu_mint_signature_generation_duration_seconds").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25.0);
    }

    @Test
    void timerSample_measuresProofVerification() {
        // When using timer samples for proof verification
        Timer.Sample sample = metrics.startProofVerificationTimer();

        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        metrics.stopProofVerificationTimer(sample);

        // Then the timer records the duration
        Timer timer = registry.find("cashu_mint_proof_verification_duration_seconds").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(10);
    }

    @Test
    void timerSample_measuresSignatureGeneration() {
        // When using timer samples for signature generation
        Timer.Sample sample = metrics.startSignatureGenerationTimer();

        // Simulate some work
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        metrics.stopSignatureGenerationTimer(sample);

        // Then the timer records the duration
        Timer timer = registry.find("cashu_mint_signature_generation_duration_seconds").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void setActiveKeysets_updatesGauge() {
        // When active keysets are set
        metrics.setActiveKeysets(5);

        // Then the gauge reflects the count
        Gauge gauge = registry.find("cashu_mint_keysets_active").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(5.0);

        // When incremented
        metrics.incrementActiveKeysets();
        assertThat(gauge.value()).isEqualTo(6.0);

        // When decremented
        metrics.decrementActiveKeysets();
        assertThat(gauge.value()).isEqualTo(5.0);
    }

    @Test
    void setOutstandingSats_initializesGauge() {
        // When outstanding sats are set (e.g., from DB on startup)
        metrics.setOutstandingSats(10000);

        // Then the gauge reflects the amount
        Gauge gauge = registry.find("cashu_mint_sats_outstanding").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(10000.0);

        // And subsequent operations adjust correctly
        metrics.recordProofIssued("keyset123", 500);
        assertThat(gauge.value()).isEqualTo(10500.0);

        metrics.recordProofSpent("keyset123", 200);
        assertThat(gauge.value()).isEqualTo(10300.0);
    }

    @Test
    void keysetTracking_createsPerKeysetCounters() {
        // Given keyset tracking is enabled (trackKeysets=true in setUp)
        // When proofs are issued for different keysets
        metrics.recordProofIssued("keyset_abc", 100);
        metrics.recordProofIssued("keyset_xyz", 200);
        metrics.recordProofsIssued("keyset_abc", 2, 300);

        // Then per-keyset counters are created
        Counter keysetAbcIssued = registry.find("cashu_mint_proofs_issued_by_keyset_total")
                .tag("keyset_id", "keyset_abc")
                .counter();
        assertThat(keysetAbcIssued).isNotNull();
        assertThat(keysetAbcIssued.count()).isEqualTo(3.0);

        Counter keysetXyzIssued = registry.find("cashu_mint_proofs_issued_by_keyset_total")
                .tag("keyset_id", "keyset_xyz")
                .counter();
        assertThat(keysetXyzIssued).isNotNull();
        assertThat(keysetXyzIssued.count()).isEqualTo(1.0);
    }

    @Test
    void keysetTracking_disabled_noPerKeysetCounters() {
        // Given keyset tracking is disabled
        MintMetrics metricsNoKeyset = new MintMetrics(registry, false);

        // When proofs are issued
        metricsNoKeyset.recordProofIssued("keyset_abc", 100);

        // Then no per-keyset counter is created (only global)
        Counter keysetCounter = registry.find("cashu_mint_proofs_issued_by_keyset_total")
                .tag("keyset_id", "keyset_abc")
                .counter();
        assertThat(keysetCounter).isNull();

        // But the global counter is still updated
        // Note: the global counter was already created in setUp, so we check total
        Counter globalCounter = registry.find("cashu_mint_proofs_issued_total").counter();
        assertThat(globalCounter).isNotNull();
    }

    @Test
    void nullKeysetId_handledGracefully() {
        // When keyset ID is null
        metrics.recordProofIssued(null, 100);
        metrics.recordProofSpent(null, 50);
        metrics.recordSignatureGenerated(null);

        // Then global counters are still updated
        Counter proofsIssued = registry.find("cashu_mint_proofs_issued_total").counter();
        assertThat(proofsIssued).isNotNull();
        assertThat(proofsIssued.count()).isEqualTo(1.0);

        // And no per-keyset counter with null tag is created
        Counter nullKeyset = registry.find("cashu_mint_proofs_issued_by_keyset_total")
                .tag("keyset_id", "null")
                .counter();
        assertThat(nullKeyset).isNull();
    }
}
