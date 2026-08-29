package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every NUT this mint knows about, paired with the evidence for the claim.
 *
 * <p>This enum is the single source of truth behind the NUT-06 {@code nuts} map.
 * Before this existed the map was a hand-written YAML block, and it drifted:
 * NUT-19 was implemented but never advertised, and entries stayed {@code true}
 * after the code behind them changed. {@code /v1/info} is a promise to wallets,
 * and a promise kept in a different file from the code it describes is a promise
 * that will eventually be broken silently.
 *
 * <p>Each constant names a <em>wiring witness</em>: the type (and optionally the
 * member) whose existence is what makes the claim true. {@code
 * NutWiringContractTest} resolves every witness by reflection, so deleting or
 * renaming an implementation fails the build rather than leaving a lie on the
 * wire.
 *
 * <h2>Why a wiring witness is not enough on its own</h2>
 *
 * <p>A witness that only proves a class exists proves only that a class exists.
 * NUT-11 was advertised {@code supported: true} on exactly that basis, and the
 * implementation was <em>not</em> interoperable: every published NUT-11 vector
 * failed, because NUT-10 secrets were re-serialized into a shape no other
 * implementation reads (cashu-lib#254). The advertisement was true about the
 * classpath and false about the behaviour, which is the only thing a wallet
 * cares about.
 *
 * <p>So a NUT that has published spec vectors also names them, in
 * {@link #specVectorSuiteClassName}. That turns the advertisement and the
 * interoperability gate into <em>the same fact</em>: the contract test executes
 * the named suite and requires the outcome to match the visibility. Vectors
 * green and the NUT withheld fails the build; vectors red — or merely disabled,
 * which is the absence of evidence rather than evidence — and the NUT advertised
 * fails the build too.
 *
 * <p>{@link Visibility#MANDATORY} NUTs are required by the spec and are not
 * listed in the map; they are declared here so that every {@code @Nut}-annotated
 * class on the classpath is accounted for. {@link Visibility#WITHHELD} NUTs are
 * implemented but cannot yet prove it, so NUT-06's "absent means unsupported"
 * is the honest answer until their vectors pass.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
@Getter
@RequiredArgsConstructor
public enum NutSupport {

    KEYS(1, Visibility.MANDATORY,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT01", null),
    KEYSETS(2, Visibility.MANDATORY,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT02", null),
    SWAP(3, Visibility.MANDATORY,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT03", null),
    MINT(4, Visibility.PAYMENT_METHODS,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT04", null),
    MELT(5, Visibility.PAYMENT_METHODS,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT05", null),
    MINT_INFO(6, Visibility.MANDATORY,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT06", null),
    STATE_CHECK(7, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT07", null),
    OVERPAID_MELT_FEES(8, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.tasks.MeltTask", null),
    RESTORE_SIGNATURES(9, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT09", null),
    WELL_KNOWN_SECRETS(10, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.tasks.validator.SpendingCondition", null),

    /**
     * P2PK spending conditions, advertised because the published vectors pass.
     *
     * <p>It was withheld until cashu-lib 0.24.0: the spending condition logic
     * was correct, but the NUT-10 secret it verified against was re-serialized
     * into a non-spec shape, so no third-party wallet's proof could verify here
     * and none of ours could verify elsewhere (cashu-lib#254).
     *
     * <p>That this now reads {@link Visibility#SIMPLE} is not a judgement call:
     * the named vector suite decides it, and the contract test fails if the
     * visibility and the vectors ever disagree again, in either direction.
     */
    P2PK_SPENDING_CONDITIONS(11, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition", null,
            // Named rather than typed: this is a test class, and main code must not depend on
            // test code. The contract test resolves it by reflection.
            "xyz.tcheeric.cashu.mint.proto.spending.Nut11TestVectorsTest"),
    DLEQ_PROOFS(12, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.service.DLEQProofGenerator", "generateProof"),
    /**
     * NUT-20 signed mint quotes. The witness is the verifier rather than the
     * request field, because a {@code pubkey} the mint accepts and never checks
     * is exactly the false claim this enum exists to prevent.
     */
    MINT_QUOTE_SIGNATURE(20, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.common.nut20.MintQuoteSignature", "isValid"),
    WEBSOCKET_SUBSCRIPTIONS(17, Visibility.WEBSOCKET,
            "xyz.tcheeric.cashu.mint.proto.nut.NUT17", null),
    CACHED_RESPONSES(19, Visibility.CACHED_RESPONSES,
            "xyz.tcheeric.cashu.mint.proto.ports.MeltSaga", "meltResponseCache");

    /**
     * How a NUT appears in the NUT-06 {@code nuts} map, if at all.
     */
    public enum Visibility {

        /** Required by the spec; never listed in the map. */
        MANDATORY,

        /**
         * Implemented, but its spec vectors do not pass, so it is not listed.
         * NUT-06 reads an absent entry as unsupported, which is the truth.
         */
        WITHHELD,

        /** Listed as {@code {"supported": true}}. */
        SIMPLE,

        /** Listed with the deployment's payment methods and amount limits. */
        PAYMENT_METHODS,

        /** Listed with the WebSocket commands the mint serves (NUT-17). */
        WEBSOCKET,

        /** Listed with the cache TTL and the cached endpoints (NUT-19). */
        CACHED_RESPONSES;

        /**
         * Answers whether a NUT with this visibility belongs in the map.
         *
         * @return true when the NUT is advertised
         */
        public boolean isAdvertised() {
            return this != MANDATORY && this != WITHHELD;
        }
    }

    private final int number;
    private final Visibility visibility;
    private final String witnessClassName;

    /**
     * Member on the witness class whose presence carries the claim, or null when
     * the class itself is witness enough.
     */
    private final String witnessMemberName;

    /**
     * Fully qualified name of the suite driving this NUT's published spec
     * vectors, or null when the spec publishes none for it.
     */
    private final String specVectorSuiteClassName;

    NutSupport(int number, Visibility visibility, String witnessClassName, String witnessMemberName) {
        this(number, visibility, witnessClassName, witnessMemberName, null);
    }

    /**
     * Answers whether the spec publishes vectors that gate this NUT's claim.
     *
     * @return true when a vector suite is named
     */
    public boolean hasSpecVectors() {
        return specVectorSuiteClassName != null;
    }

    /**
     * Returns the map key under which this NUT is advertised.
     *
     * @return the decimal NUT number as a string
     */
    public String key() {
        return Integer.toString(number);
    }

    /**
     * Finds the declaration for a NUT number.
     *
     * @param number the NUT number
     * @return the declaration, or empty when the number is undeclared
     */
    public static Optional<NutSupport> forNumber(int number) {
        return Arrays.stream(values())
                .filter(nut -> nut.number == number)
                .findFirst();
    }
}
