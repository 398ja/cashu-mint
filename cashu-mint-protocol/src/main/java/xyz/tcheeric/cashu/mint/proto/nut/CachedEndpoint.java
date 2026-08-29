package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Every route the mint advertises as cached under NUT-19, paired with the store
 * that makes the replay actually work.
 *
 * <p>NUT-19's contract is narrow and load-bearing: a wallet that replays a
 * request on a listed path must get the <em>same response back</em>, not an
 * error. The list used to be a bare {@code List.of(...)} of path strings in the
 * assembler, and it named {@code /v1/swap}, which has no response cache at all —
 * a replayed swap is rejected with {@code outputs_already_signed}, the exact
 * opposite of what the advertisement promised. A wallet safely retrying an
 * interrupted swap would have lost its signatures to an error.
 *
 * <p>So a path is only listed here together with the member whose existence is
 * what makes the replay possible, and {@code NutWiringContractTest} resolves
 * every witness by reflection. Deleting the cache now fails the build instead of
 * leaving a false promise on the wire, and no path can be added without naming
 * the store behind it.
 *
 * <p>{@code /v1/swap} is deliberately absent: {@code SwapTask} keeps no record
 * of the signatures it issued, so there is nothing to replay. Narrowing the
 * claim is the honest move — under-advertising costs a wallet one retry it could
 * have made safely, while over-advertising costs it the retry it did make.
 * Listing swap again is a matter of giving {@code SwapTask} a cache keyed on the
 * outputs fingerprint, the way the mint path is keyed on {@code outputs_hash},
 * and adding the entry here with that store as its witness.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/19.md">NUT-19</a>
 */
@Getter
@RequiredArgsConstructor
public enum CachedEndpoint {

    /**
     * NUT-04 issuance. A retry with identical outputs replays the stored blind
     * signatures rather than re-signing, witnessed by the issuance ledger row.
     */
    MINT_BOLT11("POST", "/v1/mint/bolt11",
            "xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord", "signaturesJson"),

    /**
     * NUT-05 melt. The saga stores the serialised melt response and returns it
     * verbatim on a replay.
     */
    MELT_BOLT11("POST", "/v1/melt/bolt11",
            "xyz.tcheeric.cashu.mint.proto.ports.MeltSaga", "meltResponseCache");

    private final String httpMethod;
    private final String path;

    /** Type whose presence carries the claim that this path is cached. */
    private final String witnessClassName;

    /** Member on the witness type that holds the cached response. */
    private final String witnessMemberName;
}
