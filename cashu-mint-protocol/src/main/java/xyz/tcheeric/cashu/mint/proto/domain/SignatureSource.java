package xyz.tcheeric.cashu.mint.proto.domain;

/**
 * The operation that caused the mint to issue a blind signature.
 *
 * <p>Recorded next to every stored signature so an operator reconciling issued value against
 * what backs it can tell a signature minted against a paid quote from one produced by a swap
 * (value-neutral) or by NUT-08 change on an overpaid melt.
 */
public enum SignatureSource {

    /** NUT-03: outputs signed in exchange for spent inputs. */
    SWAP,

    /** NUT-04: outputs signed against a paid or funded mint quote. */
    MINT,

    /** NUT-08: change outputs signed for the overpaid part of a melt fee reserve. */
    MELT_CHANGE
}
