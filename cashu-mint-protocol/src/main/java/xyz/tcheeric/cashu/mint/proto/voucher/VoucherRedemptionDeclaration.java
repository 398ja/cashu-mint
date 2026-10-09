package xyz.tcheeric.cashu.mint.proto.voucher;

import lombok.NonNull;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A redeemer's statement, attached to one swap, that it is crediting {@code sats} of the voucher
 * {@code voucherId} to someone (cashu-mint#540).
 *
 * <p>It travels as the {@value #HEADER} header on {@code POST /v1/swap}, in the form
 * {@code <voucher_id>:<sats>}, and only counts when the {@value #REDEEMER_HEADER} header carries a
 * token the mint trusts. The swap body stays plain NUT-03, so a wallet that is not crediting
 * anyone sends nothing and nothing changes for it.
 *
 * <p>Why the redeemer declares rather than the mint inferring: swap outputs are blinded (NUT-03,
 * NUT-10), so the mint can never see whether a swap's outputs are the voucher, plain sats or
 * anything else. What it can see is the redeemer saying "I am paying out for this", and that is
 * exactly the event that must happen at most once per issued sat.
 *
 * @param voucherId the voucher being credited
 * @param sats      the sats of it being credited, {@code > 0}
 */
public record VoucherRedemptionDeclaration(@NonNull String voucherId, long sats) {

    /** The header carrying the declaration. */
    public static final String HEADER = "X-Cashu-Voucher-Redemption";

    /** The header carrying the redeemer's token. */
    public static final String REDEEMER_HEADER = "X-Cashu-Voucher-Redeemer";

    private static final Pattern VOUCHER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public VoucherRedemptionDeclaration {
        if (!VOUCHER_ID.matcher(voucherId).matches()) {
            throw new IllegalArgumentException("voucher id must be 1-64 characters of [A-Za-z0-9-]");
        }
        if (sats <= 0) {
            throw new IllegalArgumentException("declared sats must be positive");
        }
    }

    /**
     * Parses the header value.
     *
     * @param header the raw header, may be null or blank
     * @return the declaration, or empty when there is no header
     * @throws IllegalArgumentException when a header is present but malformed
     */
    public static Optional<VoucherRedemptionDeclaration> parse(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.strip();
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException(HEADER + " must be <voucher_id>:<sats>");
        }
        long sats;
        try {
            sats = Long.parseLong(value.substring(colon + 1));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(HEADER + " sats must be a whole number");
        }
        return Optional.of(new VoucherRedemptionDeclaration(value.substring(0, colon), sats));
    }

    /** @return the first 8 characters of the voucher id, for logs */
    public String voucherIdPrefix() {
        return voucherId.length() <= 8 ? voucherId : voucherId.substring(0, 8);
    }
}
