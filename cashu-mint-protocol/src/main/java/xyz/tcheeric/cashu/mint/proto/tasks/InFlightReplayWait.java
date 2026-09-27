package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * How long a refused swap replay waits for its original, in flight on another instance, to store
 * the response it can be answered with (issue #519).
 *
 * @param pauses       the sleeps between cache lookups; their sum is the most a replay waits
 * @param settleWindow how long after a same-request hold is committed its response is still worth
 *                     waiting for. The response is stored straight after the commit, so a hold
 *                     committed longer ago than this has either stored it already or never will
 *                     (its store failed, or the entry has since expired).
 * @param clock        the time source the settle window is measured against
 * @param sleeper      how a pause is spent, replaceable so tests need not really sleep
 */
record InFlightReplayWait(@NonNull List<Duration> pauses,
                          @NonNull Duration settleWindow,
                          @NonNull Clock clock,
                          @NonNull Sleeper sleeper) {

    /**
     * Doubling from 50 ms to a 1 s cap, about 8.5 s in all. The budget is sized for the large
     * swaps that split a spend of many proofs, which take longest to sign and are the reason
     * replay matters (imani-wallet-lib#72).
     */
    private static final List<Duration> DEFAULT_PAUSES = List.of(
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200),
            Duration.ofMillis(400), Duration.ofMillis(800), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));

    /**
     * Far wider than the milliseconds between a commit and its cache store, because the hold's
     * {@code updatedAt} is stamped by the instance that ran the original and compared here against
     * this instance's clock. A narrow window would turn a few seconds of clock skew between
     * replicas into a refused replay. The cost of width is only paid when an original committed
     * but failed to store its response, and then it is one bounded wait before the same refusal.
     */
    private static final Duration DEFAULT_SETTLE_WINDOW = Duration.ofSeconds(30);

    static final InFlightReplayWait DEFAULT = new InFlightReplayWait(
            DEFAULT_PAUSES, DEFAULT_SETTLE_WINDOW, Clock.systemUTC(), Thread::sleep);

    InFlightReplayWait {
        pauses = List.copyOf(pauses);
    }

    /** Spends one pause. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
