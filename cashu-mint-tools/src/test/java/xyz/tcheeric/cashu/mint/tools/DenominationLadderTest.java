package xyz.tcheeric.cashu.mint.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The denomination ladder, pinned to the property that makes it correct.
 *
 * <p>A ladder is not just a list of sizes. It decides how proof count grows with the amount, and
 * getting that wrong is invisible until real money is at stake:
 *
 * <ul>
 *   <li>While the ladder <b>reaches</b> the amount, a greedy largest-first split uses each
 *       denomination at most once, so proof count is the amount's POPCOUNT — bounded by the
 *       number of keys however large the amount is.
 *   <li>Once the amount <b>outgrows</b> the ladder, the top denomination is used repeatedly and
 *       proof count scales LINEARLY with magnitude. There is no bound at all.
 * </ul>
 *
 * <p>That second regime is what shipped. Against the 1..1024 ladder a EUR 25.00 sale (33,246 sat)
 * was 39 proofs rather than 8, and a EUR 1000 sale was 1,302 — so a token-size ceiling that looked
 * generous refused ordinary trade, capping merchants at about EUR 18 a sale. See
 * 398ja/imani-gateway-portal#43.
 *
 * <p>These tests assert the SHAPE rather than the literal list, so a future edit that shortens the
 * ladder fails here with the reason rather than passing and moving the cost somewhere nobody is
 * looking.
 */
class DenominationLadderTest {

    /**
     * The largest single amount the mint will sign, from staging's NUT-04/05 cap
     * (imani-deploy#59). The ladder has to reach this or the linear regime is reachable inside
     * what the mint itself permits.
     */
    private static final long MINT_PER_OPERATION_CAP = 10_000_000L;

    /** Proof count under a greedy largest-first split, which is how the mint actually splits. */
    private static int proofCount(long amount, List<Integer> ladder) {
        int proofs = 0;
        long remaining = amount;
        for (int denomination : ladder.stream().sorted((a, b) -> b - a).toList()) {
            while (remaining >= denomination) {
                remaining -= denomination;
                proofs++;
            }
        }
        return remaining == 0 ? proofs : -1;
    }

    @Test
    @DisplayName("the ladder reaches the mint's own per-operation cap")
    void reachesTheCap() {
        // THE property. Below the cap the mint will happily sign amounts the ladder cannot
        // represent efficiently, which is the regime that produced the outage.
        int top = MintPreloadDataGenerator.DEFAULT_DENOMINATIONS.stream()
                .mapToInt(Integer::intValue).max().orElseThrow();

        assertTrue((long) top * 2 >= MINT_PER_OPERATION_CAP,
                "the top denomination must be within a factor of two of the cap, so every "
                        + "amount the mint can sign is representable in one pass of the ladder; "
                        + "top was " + top);
    }

    @Test
    @DisplayName("proof count tracks popcount, not magnitude, across the whole signable range")
    void proofCountTracksPopcount() {
        List<Integer> ladder = MintPreloadDataGenerator.DEFAULT_DENOMINATIONS;
        int keys = ladder.size();

        // Sampled across the range rather than at convenient points: a power of two needs one
        // proof on any ladder, so testing those would pass on the broken one too.
        for (long amount = 1; amount <= MINT_PER_OPERATION_CAP; amount += 99_991) {
            int proofs = proofCount(amount, ladder);
            assertTrue(proofs >= 1 && proofs <= keys,
                    amount + " sat must cost at most one proof per key (" + keys + "), not one "
                            + "per multiple of the top denomination; was " + proofs);
        }
    }

    @Test
    @DisplayName("the sale that was refused is back to single figures")
    void theRefusedSaleIsCheapAgain() {
        // EUR 25.00 at ~EUR 75.2k/BTC. 39 proofs on the old ladder; popcount is 8.
        assertEquals(Long.bitCount(33_246),
                proofCount(33_246, MintPreloadDataGenerator.DEFAULT_DENOMINATIONS),
                "EUR 25.00 was 39 proofs on the 1..1024 ladder; it must be its popcount");
    }

    @Test
    @DisplayName("every denomination is a power of two, and the ladder has no gaps")
    void isAContiguousPowerOfTwoLadder() {
        List<Integer> ladder = MintPreloadDataGenerator.DEFAULT_DENOMINATIONS;

        // Gaps are worse than a short ladder: they break the popcount property in the MIDDLE of
        // the range, where it is far harder to notice than at the top.
        for (int i = 0; i < ladder.size(); i++) {
            assertEquals(1 << i, ladder.get(i), "position " + i + " must be 2^" + i);
        }

        // A 1 denomination is what makes every integer amount representable at all.
        assertEquals(1, ladder.get(0), "without a 1 not every amount is representable");
    }

    @Test
    @DisplayName("an amount above the ladder still resolves, just expensively")
    void aboveTheLadderStillResolves() {
        // Not a failure mode to hide: it is the regime the old ladder lived in, and it must stay
        // visible as a cost rather than becoming an error.
        List<Integer> shortLadder = List.of(1, 2, 4, 8);
        assertTrue(proofCount(100, shortLadder) > Long.bitCount(100),
                "outgrowing the ladder must cost more proofs, which is the regime that shipped");
    }
}
