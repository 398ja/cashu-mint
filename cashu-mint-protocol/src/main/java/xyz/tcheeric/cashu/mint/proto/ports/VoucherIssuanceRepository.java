package xyz.tcheeric.cashu.mint.proto.ports;

import java.util.Optional;

/**
 * Spec 003 — append-only ledger of voucher issuances. Inserts go through
 * {@link #insertIfAbsent} so a NUT-19 idempotent replay does not write a
 * second row for the same voucher_quote_id.
 */
public interface VoucherIssuanceRepository {

    Optional<VoucherIssuance> findByVoucherQuoteId(String voucherQuoteId);

    /**
     * Inserts a row only if no row exists for the given
     * {@code voucherQuoteId}. Returns the persisted view either way (the
     * existing row when a duplicate insert was suppressed).
     */
    VoucherIssuance insertIfAbsent(VoucherIssuance row);
}
