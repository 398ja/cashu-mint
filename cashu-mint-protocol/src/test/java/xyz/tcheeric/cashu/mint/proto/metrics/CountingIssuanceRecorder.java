package xyz.tcheeric.cashu.mint.proto.metrics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Counting test double for {@link IssuanceMetricsRecorder}.
 *
 * <p>Protocol-module tests assert that the domain called the right recorder
 * method; the mapping from method to Micrometer <em>name</em> is asserted in
 * {@code cashu-mint-observability} where the names are declared, and on the
 * real scrape in the ITs. Keeping name assertions out of here is what stops a
 * rename from having to be made in four places.
 */
public final class CountingIssuanceRecorder implements IssuanceMetricsRecorder {

    /** The events this recorder can observe. */
    public enum Event { QUOTE_EXPIRED, AMOUNT_MISMATCH, CROSS_CHECK_FAILURE, IDEMPOTENT_REPLAY, RATE_LIMIT_BREACH }

    private final Map<Event, Integer> counts = new EnumMap<>(Event.class);

    /**
     * @param event the event to read
     * @return how many times {@code event} was recorded
     */
    public int count(Event event) {
        return counts.getOrDefault(event, 0);
    }

    private void record(Event event) {
        counts.merge(event, 1, Integer::sum);
    }

    @Override
    public void quoteExpired() {
        record(Event.QUOTE_EXPIRED);
    }

    @Override
    public void amountMismatch() {
        record(Event.AMOUNT_MISMATCH);
    }

    @Override
    public void crossCheckFailure() {
        record(Event.CROSS_CHECK_FAILURE);
    }

    @Override
    public void idempotentReplay() {
        record(Event.IDEMPOTENT_REPLAY);
    }

    @Override
    public void rateLimitBreach() {
        record(Event.RATE_LIMIT_BREACH);
    }
}
