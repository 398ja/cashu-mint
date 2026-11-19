package xyz.tcheeric.cashu.mint.proto.util;

import java.util.regex.Pattern;

/**
 * The voucher id a voucher quote may be bound to at creation.
 *
 * <p>The binding travels as the {@value #HEADER} request header on
 * {@code POST /v1/mint/quote/voucher/{method}}, so the NUT-04 body stays standard. The mint
 * echoes the id it recorded in the same response header, which lets a caller tell a mint that
 * recorded the binding from one that predates it.
 */
public final class VoucherQuoteBinding {

    /** Request and response header carrying the bound voucher id. */
    public static final String HEADER = "X-Cashu-Voucher-Id";

    /** A lowercase or uppercase canonical UUID, the form issuers mint voucher ids in. */
    private static final Pattern WELL_FORMED =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private VoucherQuoteBinding() {
    }

    /** Whether {@code voucherId} has the canonical UUID form a binding requires. */
    public static boolean isWellFormed(String voucherId) {
        return voucherId != null && WELL_FORMED.matcher(voucherId).matches();
    }
}
