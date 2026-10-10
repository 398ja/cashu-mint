package xyz.tcheeric.cashu.mint.proto.voucher;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;
import xyz.tcheeric.cashu.voucher.domain.UnlockedVoucherBlob;
import xyz.tcheeric.cashu.voucher.domain.VoucherMetadata;

import java.util.List;
import java.util.Optional;

/**
 * The terms of one voucher proof that the redemption cap needs: which voucher, and how many sats
 * were issued for it (cashu-mint#540).
 *
 * <p>The cap comes from the coupon's own signed terms, {@code ceil(face_value / issuance_ratio)}.
 * The issuer fixes {@code issuance_ratio = face_value / token_amount} when it issues, and signs
 * both, and the mint has already verified that signature and bound its signer before this runs
 * (#526, #527). So this is the issued sats, without a database lookup and for legacy coupons too.
 * The mint's own {@code voucher_quote.original_token_amount} is not usable here: production
 * coupons are relabelled from plain sats by a swap, and their voucher id is not a quote id.
 *
 * @param voucherId the voucher id, never null
 * @param capSats   the sats issued for the voucher, or null when the terms do not say
 */
public record VoucherTerms(@NonNull String voucherId, Long capSats) {

    /**
     * Float rounding slack. {@code 500 / (500 / 7000.0)} is {@code 7000.000000001}, which must
     * read as 7000 and not 7001. Larger errors round up, towards the holder.
     */
    private static final double ROUNDING_SLACK = 1e-6;

    /**
     * Reads a proof secret's voucher terms.
     *
     * @param secret any proof secret
     * @return the terms, or empty when the secret is not a voucher or names no voucher id
     */
    public static Optional<VoucherTerms> of(Secret secret) {
        if (!(secret instanceof WellKnownSecret wks) || !VoucherMetadata.isVoucherCarrying(wks)) {
            return Optional.empty();
        }
        WellKnownSecret readable = wks;
        if (wks.getKind() == WellKnownSecret.Kind.VOUCHER && isTagless(wks)) {
            VoucherSecret fromBlob = UnlockedVoucherBlob.read(wks);
            if (fromBlob == null) {
                return Optional.empty();
            }
            readable = fromBlob;
        }
        String voucherId = readable instanceof VoucherSecret vs && vs.getVoucherId() != null
                ? vs.getVoucherId().toString()
                : VoucherMetadata.voucherId(readable);
        if (voucherId == null || voucherId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new VoucherTerms(voucherId,
                cap(tagLong(readable, VoucherTags.FACE_VALUE), tagDouble(readable, VoucherTags.ISSUANCE_RATIO))));
    }

    /**
     * {@code ceil(face / ratio)}, or null when either is missing or not positive. An absent ratio
     * means 1.0, as {@code VoucherSecret.getIssuanceRatio} reads it.
     */
    static Long cap(Long faceValue, Double issuanceRatio) {
        if (faceValue == null || faceValue <= 0) {
            return null;
        }
        double ratio = issuanceRatio == null ? 1.0 : issuanceRatio;
        if (!(ratio > 0) || Double.isInfinite(ratio)) {
            return null;
        }
        double sats = Math.ceil(faceValue / ratio - ROUNDING_SLACK);
        if (!(sats >= 1) || sats > Long.MAX_VALUE / 2) {
            return null;
        }
        return (long) sats;
    }

    private static boolean isTagless(WellKnownSecret secret) {
        return secret.getTags() == null || secret.getTags().isEmpty();
    }

    private static String tagValue(WellKnownSecret secret, String key) {
        WellKnownSecret.Tag tag = secret.getTag(key);
        if (tag == null) {
            return null;
        }
        List<Object> values = tag.getValues();
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return null;
        }
        return String.valueOf(values.get(0)).trim();
    }

    private static Long tagLong(WellKnownSecret secret, String key) {
        String raw = tagValue(secret, key);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static Double tagDouble(WellKnownSecret secret, String key) {
        String raw = tagValue(secret, key);
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException malformed) {
            return -1.0;
        }
    }
}
