package xyz.tcheeric.cashu.mint.rest.config;

import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Optional;

/**
 * The voucher ledger port the mint hands to cashu-voucher's {@code VoucherService}, whose
 * constructor requires one. It publishes nothing and knows no status.
 *
 * <p>The mint does not take part in the v1 public voucher ledger (imani-gateway-customer#310):
 * its Nostr ledger client never connected, nothing read what it would have published, and the
 * mint's own redemption cap is its Postgres table, not Nostr.
 */
final class NoOpVoucherLedgerPort implements VoucherLedgerPort {

    @Override
    public void publish(SignedVoucher voucher, VoucherStatus status) {
        // The mint publishes no ledger events.
    }

    @Override
    public Optional<VoucherStatus> queryStatus(String voucherId) {
        return Optional.empty();
    }

    @Override
    public void updateStatus(String voucherId, VoucherStatus newStatus) {
        // The mint publishes no ledger events.
    }
}
