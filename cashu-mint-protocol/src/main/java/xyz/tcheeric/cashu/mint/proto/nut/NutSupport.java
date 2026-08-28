package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every NUT this mint knows about, paired with the code that implements it.
 *
 * <p>This enum is the single source of truth behind the NUT-06 {@code nuts} map.
 * Before this existed the map was a hand-written YAML block, and it drifted:
 * NUT-19 was implemented but never advertised, and entries stayed {@code true}
 * after the code behind them changed. {@code /v1/info} is a promise to wallets,
 * and a promise kept in a different file from the code it describes is a promise
 * that will eventually be broken silently.
 *
 * <p>Each constant names a <em>wiring witness</em>: the type (and optionally the
 * member) whose existence is what makes the claim true. {@code NutWiringTest}
 * resolves every witness by reflection, so deleting or renaming an
 * implementation fails the build rather than leaving a lie on the wire.
 *
 * <p>{@link Visibility#MANDATORY} NUTs are required by the spec and are not
 * listed in the map; they are declared here so that every {@code @Nut}-annotated
 * class on the classpath is accounted for.
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
    P2PK_SPENDING_CONDITIONS(11, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition", null),
    DLEQ_PROOFS(12, Visibility.SIMPLE,
            "xyz.tcheeric.cashu.mint.proto.service.DLEQProofGenerator", "generateProof"),
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
            return this != MANDATORY;
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
