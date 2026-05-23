package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;

/**
 * Spec 003 — append-only join row from {@code voucher_quote} to
 * {@code voucher_funding} to the spec-001 {@code issuance_record}.
 * Answers the FR-005 audit question "given proof X, return its funding
 * source" in one indexed JOIN.
 *
 * <p>Spec: {@code specs/003-voucher-quote-durability/data-model.md} §
 * VoucherIssuance.
 */
public interface VoucherIssuance {

    String voucherQuoteId();

    String fundingId();

    /** Mirrors {@code IssuanceRecord.quoteId}; same value as {@link #voucherQuoteId()} via the R1 namespace invariant. */
    String issuanceId();

    String outputsHash();

    Instant issuedAt();
}
