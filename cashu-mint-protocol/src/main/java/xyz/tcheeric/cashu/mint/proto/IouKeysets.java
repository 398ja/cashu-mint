package xyz.tcheeric.cashu.mint.proto;

import xyz.tcheeric.cashu.common.KeySet;

/**
 * Marks the Dalia "IOU" keyset — the dedicated, zero-value keyset used for loan IOU markers
 * (Dalia Phase 9). The keyset is identified by convention via its {@code unit} (no schema flag),
 * mirroring how voucher proofs are special-cased. Its proofs carry no monetary value: a single
 * {@code 0} denomination is issued, and all value operations (melt/swap) on it are refused.
 */
public final class IouKeysets {

    /** The reserved unit string that designates the IOU keyset. */
    public static final String IOU_UNIT = "iou";

    private IouKeysets() {
    }

    /** Whether a unit string is the IOU unit (case-insensitive). */
    public static boolean isIouUnit(String unit) {
        return IOU_UNIT.equalsIgnoreCase(unit);
    }

    /** Whether a resolved keyset is the IOU keyset (by its unit). */
    public static boolean isIouKeyset(KeySet keySet) {
        return keySet != null && isIouUnit(keySet.getUnit());
    }
}
