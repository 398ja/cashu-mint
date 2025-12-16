package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core metrics for Cashu Mint operations.
 *
 * <p>This class provides metrics instrumentation for:
 * <ul>
 *   <li>Proof lifecycle (issued, spent, double-spend attempts)</li>
 *   <li>Business metrics (sats issued, redeemed, outstanding)</li>
 *   <li>Signature operations (generated, verified)</li>
 *   <li>Keyset tracking</li>
 * </ul>
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_*}
 */
@Slf4j
public class MintMetrics {

    private static final String METRIC_PREFIX = "cashu_mint_";

    private final MeterRegistry registry;
    private final boolean trackKeysets;

    // Atomic counters for gauges
    private final AtomicLong satsOutstanding;
    private final AtomicLong activeKeysets;

    // Cached counters by keyset to avoid re-registration
    private final ConcurrentHashMap<String, Counter> proofsIssuedByKeyset = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> proofsSpentByKeyset = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> signaturesGeneratedByKeyset = new ConcurrentHashMap<>();

    // Global counters (no keyset tag)
    private final Counter proofsIssuedTotal;
    private final Counter proofsSpentTotal;
    private final Counter doubleSpendAttempts;
    private final Counter signaturesGeneratedTotal;
    private final Counter signaturesVerifiedTotal;

    // Business counters
    private final Counter satsIssuedTotal;
    private final Counter satsRedeemedTotal;

    // Timers
    private final Timer proofVerificationTimer;
    private final Timer signatureGenerationTimer;

    /**
     * Creates a new MintMetrics instance.
     *
     * @param registry the Micrometer registry to use
     * @param trackKeysets whether to track metrics per keyset (increases cardinality)
     */
    public MintMetrics(MeterRegistry registry, boolean trackKeysets) {
        this.registry = registry;
        this.trackKeysets = trackKeysets;

        // Initialize atomic values for gauges
        this.satsOutstanding = new AtomicLong(0);
        this.activeKeysets = new AtomicLong(0);

        // Register gauges
        Gauge.builder(METRIC_PREFIX + "sats_outstanding", satsOutstanding, AtomicLong::get)
                .description("Current outstanding sats (issued - redeemed)")
                .tag("unit", "sat")
                .register(registry);

        Gauge.builder(METRIC_PREFIX + "keysets_active", activeKeysets, AtomicLong::get)
                .description("Number of active keysets")
                .register(registry);

        // Global counters
        this.proofsIssuedTotal = Counter.builder(METRIC_PREFIX + "proofs_issued_total")
                .description("Total proofs issued")
                .register(registry);

        this.proofsSpentTotal = Counter.builder(METRIC_PREFIX + "proofs_spent_total")
                .description("Total proofs marked as spent")
                .register(registry);

        this.doubleSpendAttempts = Counter.builder(METRIC_PREFIX + "proofs_double_spend_total")
                .description("Double-spend attempts detected")
                .register(registry);

        this.signaturesGeneratedTotal = Counter.builder(METRIC_PREFIX + "signatures_generated_total")
                .description("Total blind signatures generated")
                .register(registry);

        this.signaturesVerifiedTotal = Counter.builder(METRIC_PREFIX + "signatures_verified_total")
                .description("Total signatures verified")
                .register(registry);

        // Business counters
        this.satsIssuedTotal = Counter.builder(METRIC_PREFIX + "sats_issued_total")
                .description("Total sats issued")
                .tag("unit", "sat")
                .register(registry);

        this.satsRedeemedTotal = Counter.builder(METRIC_PREFIX + "sats_redeemed_total")
                .description("Total sats redeemed")
                .tag("unit", "sat")
                .register(registry);

        // Timers - minimumExpectedValue must be > 0 when percentiles-histogram is enabled
        this.proofVerificationTimer = Timer.builder(METRIC_PREFIX + "proof_verification_duration_seconds")
                .description("Time to verify proofs")
                .minimumExpectedValue(java.time.Duration.ofMillis(1))
                .register(registry);

        this.signatureGenerationTimer = Timer.builder(METRIC_PREFIX + "signature_generation_duration_seconds")
                .description("Time to generate blind signatures")
                .minimumExpectedValue(java.time.Duration.ofMillis(1))
                .register(registry);

        log.debug("MintMetrics initialized with trackKeysets={}", trackKeysets);
    }

    /**
     * Records a proof being issued.
     *
     * @param keysetId the keyset ID that issued the proof
     * @param amount the amount of the proof in sats
     */
    public void recordProofIssued(String keysetId, long amount) {
        proofsIssuedTotal.increment();
        satsIssuedTotal.increment(amount);
        satsOutstanding.addAndGet(amount);

        if (trackKeysets && keysetId != null) {
            getOrCreateProofsIssuedCounter(keysetId).increment();
        }

        log.trace("Recorded proof issued: keysetId={}, amount={}", keysetId, amount);
    }

    /**
     * Records multiple proofs being issued.
     *
     * @param keysetId the keyset ID that issued the proofs
     * @param count number of proofs issued
     * @param totalAmount total amount of all proofs in sats
     */
    public void recordProofsIssued(String keysetId, int count, long totalAmount) {
        proofsIssuedTotal.increment(count);
        satsIssuedTotal.increment(totalAmount);
        satsOutstanding.addAndGet(totalAmount);

        if (trackKeysets && keysetId != null) {
            getOrCreateProofsIssuedCounter(keysetId).increment(count);
        }

        log.trace("Recorded {} proofs issued: keysetId={}, totalAmount={}", count, keysetId, totalAmount);
    }

    /**
     * Records a proof being spent.
     *
     * @param keysetId the keyset ID of the proof
     * @param amount the amount of the proof in sats
     */
    public void recordProofSpent(String keysetId, long amount) {
        proofsSpentTotal.increment();
        satsRedeemedTotal.increment(amount);
        satsOutstanding.addAndGet(-amount);

        if (trackKeysets && keysetId != null) {
            getOrCreateProofsSpentCounter(keysetId).increment();
        }

        log.trace("Recorded proof spent: keysetId={}, amount={}", keysetId, amount);
    }

    /**
     * Records multiple proofs being spent.
     *
     * @param keysetId the keyset ID of the proofs
     * @param count number of proofs spent
     * @param totalAmount total amount of all proofs in sats
     */
    public void recordProofsSpent(String keysetId, int count, long totalAmount) {
        proofsSpentTotal.increment(count);
        satsRedeemedTotal.increment(totalAmount);
        satsOutstanding.addAndGet(-totalAmount);

        if (trackKeysets && keysetId != null) {
            getOrCreateProofsSpentCounter(keysetId).increment(count);
        }

        log.trace("Recorded {} proofs spent: keysetId={}, totalAmount={}", count, keysetId, totalAmount);
    }

    /**
     * Records a double-spend attempt.
     */
    public void recordDoubleSpendAttempt() {
        doubleSpendAttempts.increment();
        log.debug("Recorded double-spend attempt");
    }

    /**
     * Records a double-spend attempt with keyset context.
     *
     * @param keysetId the keyset ID involved in the double-spend attempt
     */
    public void recordDoubleSpendAttempt(String keysetId) {
        doubleSpendAttempts.increment();
        log.debug("Recorded double-spend attempt for keysetId={}", keysetId);
    }

    /**
     * Records a blind signature being generated.
     *
     * @param keysetId the keyset ID used for signing
     */
    public void recordSignatureGenerated(String keysetId) {
        signaturesGeneratedTotal.increment();

        if (trackKeysets && keysetId != null) {
            getOrCreateSignaturesGeneratedCounter(keysetId).increment();
        }
    }

    /**
     * Records multiple blind signatures being generated.
     *
     * @param keysetId the keyset ID used for signing
     * @param count number of signatures generated
     */
    public void recordSignaturesGenerated(String keysetId, int count) {
        signaturesGeneratedTotal.increment(count);

        if (trackKeysets && keysetId != null) {
            getOrCreateSignaturesGeneratedCounter(keysetId).increment(count);
        }
    }

    /**
     * Records a signature verification.
     */
    public void recordSignatureVerified() {
        signaturesVerifiedTotal.increment();
    }

    /**
     * Records multiple signature verifications.
     *
     * @param count number of signatures verified
     */
    public void recordSignaturesVerified(int count) {
        signaturesVerifiedTotal.increment(count);
    }

    /**
     * Records proof verification duration.
     *
     * @param durationNanos duration in nanoseconds
     */
    public void recordProofVerificationTime(long durationNanos) {
        proofVerificationTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records signature generation duration.
     *
     * @param durationNanos duration in nanoseconds
     */
    public void recordSignatureGenerationTime(long durationNanos) {
        signatureGenerationTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Gets a timer sample for measuring proof verification.
     *
     * @return a new timer sample
     */
    public Timer.Sample startProofVerificationTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops a proof verification timer sample.
     *
     * @param sample the timer sample to stop
     */
    public void stopProofVerificationTimer(Timer.Sample sample) {
        sample.stop(proofVerificationTimer);
    }

    /**
     * Gets a timer sample for measuring signature generation.
     *
     * @return a new timer sample
     */
    public Timer.Sample startSignatureGenerationTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops a signature generation timer sample.
     *
     * @param sample the timer sample to stop
     */
    public void stopSignatureGenerationTimer(Timer.Sample sample) {
        sample.stop(signatureGenerationTimer);
    }

    /**
     * Sets the number of active keysets.
     *
     * @param count number of active keysets
     */
    public void setActiveKeysets(long count) {
        activeKeysets.set(count);
    }

    /**
     * Increments the active keyset count.
     */
    public void incrementActiveKeysets() {
        activeKeysets.incrementAndGet();
    }

    /**
     * Decrements the active keyset count.
     */
    public void decrementActiveKeysets() {
        activeKeysets.decrementAndGet();
    }

    /**
     * Gets the current outstanding sats value.
     *
     * @return current outstanding sats
     */
    public long getOutstandingSats() {
        return satsOutstanding.get();
    }

    /**
     * Sets the outstanding sats value (for initialization from DB).
     *
     * @param amount the outstanding sats amount
     */
    public void setOutstandingSats(long amount) {
        satsOutstanding.set(amount);
    }

    // Helper methods for per-keyset counters

    private Counter getOrCreateProofsIssuedCounter(String keysetId) {
        return proofsIssuedByKeyset.computeIfAbsent(keysetId, id ->
                Counter.builder(METRIC_PREFIX + "proofs_issued_by_keyset_total")
                        .description("Proofs issued by keyset")
                        .tag("keyset_id", id)
                        .register(registry));
    }

    private Counter getOrCreateProofsSpentCounter(String keysetId) {
        return proofsSpentByKeyset.computeIfAbsent(keysetId, id ->
                Counter.builder(METRIC_PREFIX + "proofs_spent_by_keyset_total")
                        .description("Proofs spent by keyset")
                        .tag("keyset_id", id)
                        .register(registry));
    }

    private Counter getOrCreateSignaturesGeneratedCounter(String keysetId) {
        return signaturesGeneratedByKeyset.computeIfAbsent(keysetId, id ->
                Counter.builder(METRIC_PREFIX + "signatures_generated_by_keyset_total")
                        .description("Signatures generated by keyset")
                        .tag("keyset_id", id)
                        .register(registry));
    }
}
