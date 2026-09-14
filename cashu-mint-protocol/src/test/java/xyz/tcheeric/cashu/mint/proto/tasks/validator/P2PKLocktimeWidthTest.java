package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A P2PK locktime past 2038 must behave correctly (issue #431).
 *
 * <p>`P2PKSecret.getLockTime()` returns `long`. This test pins why that matters, and corrects the
 * record on what the pre-fix bug actually was.
 *
 * <p>The comment in {@link P2PKSpendingCondition} used to claim a narrowed `int` locktime
 * "wrapped negative, which this comparison then read as 'long expired' and unlocked the proof".
 * Working through it shows the opposite: the guard has always been `locktime > 0 &&`, and a
 * wrapped value is negative, so it fails that test and the locktime reads as *not* passed. The
 * real failure was a proof stuck locked for ever, with its refund pathway — reachable only after
 * the locktime — unreachable too. Funds stuck, not funds stealable.
 *
 * <p>Recorded as a test rather than only a comment because a claim about security behaviour that
 * nothing checks is how the wrong version survived in the first place.
 */
class P2PKLocktimeWidthTest {

    /** A Unix timestamp in 2052, comfortably past the 2038 signed-int boundary. */
    private static final long POST_2038 = 2_600_000_000L;

    /** The evaluation the spending condition performs. */
    private static boolean locktimeHasPassed(long locktime) {
        return locktime > 0 && locktime < System.currentTimeMillis() / 1000;
    }

    /** As a long, a future locktime is correctly still in force. */
    @Test
    void aPost2038LocktimeIsStillInTheFuture() {
        assertThat(locktimeHasPassed(POST_2038))
                .as("a locktime in 2052 has not passed")
                .isFalse();
    }

    /**
     * Narrowing it wraps negative — and the `> 0` guard then reads it as *not* passed, which is
     * the stuck-funds failure rather than the unlock the old comment described.
     */
    @Test
    void narrowingToIntWrapsNegativeAndReadsAsNotPassed() {
        int narrowed = (int) POST_2038;

        assertThat(narrowed).as("2052 does not fit in a signed int").isNegative();
        assertThat(locktimeHasPassed(narrowed))
                .as("a wrapped value fails the `> 0` guard, so the proof stays locked — "
                        + "the opposite of the unlock the original comment claimed")
                .isFalse();
    }

    /** A past locktime does pass, so the guard is not simply always false. */
    @Test
    void aPastLocktimeHasPassed() {
        long lastYear = (System.currentTimeMillis() / 1000) - 31_536_000L;

        assertThat(locktimeHasPassed(lastYear)).isTrue();
    }

    /** Absent (zero) means no locktime at all, which must never read as expired. */
    @Test
    void anAbsentLocktimeNeverPasses() {
        assertThat(locktimeHasPassed(0)).isFalse();
    }
}
