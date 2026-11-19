package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.metrics.KeysetRedemptionMetricsRecorder;

/**
 * Micrometer implementation of the keyset redemption cutoff recorder (cashu-mint#551).
 *
 * <p>Tagged by keyset because that is the question the counter answers: how many late holders a
 * given retired keyset still has. The label is bounded by the keysets an operator retired.
 */
public class MicrometerKeysetRedemptionMetricsRecorder implements KeysetRedemptionMetricsRecorder {

    static final String REFUSED = "cashu_mint_keyset_redemption_refused_total";

    private final MeterRegistry registry;

    public MicrometerKeysetRedemptionMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void refused(String keysetId, String operation) {
        counter(keysetId, operation).increment();
    }

    /** Declared lazily per keyset: no keyset is known before an operator retires one. */
    Counter counter(String keysetId, String operation) {
        return Counter.builder(REFUSED)
                .description("Inputs refused because their keyset is past its redemption cutoff")
                .tag("keyset", keysetId)
                .tag("operation", operation)
                .register(registry);
    }
}
