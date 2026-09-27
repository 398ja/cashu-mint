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
 * <p>{@code /v1/swap} was once listed with no cache behind it and removed for
 * that reason. It is listed again because {@code SwapTask} now stores every
 * successful response under a fingerprint of the request's inputs and outputs
 * (issue #482), and the entry below names that store.
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
            "xyz.tcheeric.cashu.mint.proto.ports.MeltSaga", "meltResponseCache"),

    /**
     * NUT-03 swap. A replay of the identical request is answered from the
     * swap response cache, keyed on the inputs and the outputs together so that
     * two different swaps asking for the same outputs never share an entry.
     *
     * <p>The witness is the replay step {@code SwapTask} calls, not the
     * {@code SwapResponseCache} port: the port's methods are interface defaults
     * that would survive the swap path ceasing to consult it, which is exactly
     * the regression this entry must catch.
     */
    SWAP("POST", "/v1/swap",
            "xyz.tcheeric.cashu.mint.proto.tasks.SwapResponseReplay", "previousResponse");

    private final String httpMethod;
    private final String path;

    /** Type whose presence carries the claim that this path is cached. */
    private final String witnessClassName;

    /** Member on the witness type that holds the cached response. */
    private final String witnessMemberName;
}
