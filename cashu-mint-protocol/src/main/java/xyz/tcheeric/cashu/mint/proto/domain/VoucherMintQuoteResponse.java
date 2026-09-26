package xyz.tcheeric.cashu.mint.proto.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;

/**
 * A NUT-04 mint-quote response for a voucher quote, plus what its invoice actually charges.
 *
 * <p>A voucher quote's invoice charges a fee (10% of the face value by default) while the quote
 * entitles the payer to mint the whole face value. NUT-04's accounting fields describe the
 * entitlement, and must: {@code amount_issued} may not exceed {@code amount_paid}, and a wallet
 * mints {@code amount_paid - amount_issued}. So {@code amount_paid} carries the face value, and
 * the price goes in {@code charged_amount}, a field NUT-04 lets a method add and requires wallets
 * to ignore when they do not know it (cashu-mint#499). A verifier that needs to know whether a
 * sale's price was paid compares {@code charged_amount} with it, never {@code amount_paid}.
 */
@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class VoucherMintQuoteResponse extends PostMintQuoteResponse {

    /** What the quote's invoice charges, in the quote's unit: the voucher fee, not its face value. */
    @JsonProperty("charged_amount")
    private final long chargedAmount;

    /**
     * @param quote         the NUT-04 view of the voucher quote, with the face value as its
     *                      entitlement
     * @param chargedAmount what the quote's invoice charges
     */
    public VoucherMintQuoteResponse(PostMintQuoteResponse quote, long chargedAmount) {
        super(quote.getQuoteId(), quote.getRequest(), quote.getAmount(), quote.getUnit(),
                quote.getPubkey(), quote.getMethod(), quote.getAmountPaid(), quote.getAmountIssued(),
                quote.getUpdatedAt(), quote.getState(), quote.isPaid(), quote.getExpiry());
        this.chargedAmount = chargedAmount;
    }
}
