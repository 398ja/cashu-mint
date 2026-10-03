package xyz.tcheeric.cashu.mint.proto.voucher;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.ports.TrustedVoucherSigners;
import xyz.tcheeric.cashu.voucher.domain.VoucherMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Ties a voucher's signing key to the issuer it names (cashu-mint#527).
 *
 * <p>The issuer signature check verifies a voucher against the {@code issuer_pubkey} the voucher
 * itself carries, so a voucher signed with an attacker's own key verifies perfectly. This is the
 * second half of that check: is the key that signed one the mint recognises for this issuer?
 * It runs only once the signature has verified, so it never has to reason about a forged one.
 *
 * <p>What happens to an untrusted signer depends on the {@link VoucherIssuerBindingMode}. A
 * binding in {@code ENFORCE} with nothing trusted cannot be built: it would refuse every signed
 * voucher, and doing that silently is worse than refusing to start.
 */
@Slf4j
public final class VoucherIssuerBinding {

    private static final int LOG_HASH_HEX_LENGTH = 12;

    private final VoucherIssuerBindingMode mode;
    private final TrustedVoucherSigners trustedSigners;

    /**
     * @param mode           what to do with an untrusted signer
     * @param trustedSigners the keys the mint recognises
     * @throws IllegalStateException when {@code mode} is {@code ENFORCE} and nothing is trusted
     */
    public VoucherIssuerBinding(@NonNull VoucherIssuerBindingMode mode,
                                @NonNull TrustedVoucherSigners trustedSigners) {
        if (mode == VoucherIssuerBindingMode.ENFORCE && trustedSigners.isEmpty()) {
            throw new IllegalStateException("cashu.mint.voucher.issuer-binding=enforce with no "
                    + "cashu.mint.voucher.issuer-keys.* and no cashu.mint.voucher.trusted-signers "
                    + "configured would refuse every signed voucher. Configure the signing keys, "
                    + "or use issuer-binding=log until they are known.");
        }
        this.mode = mode;
        this.trustedSigners = trustedSigners;
    }

    /**
     * What a mint gets when nothing was configured: the property's own default, {@code LOG},
     * with nothing trusted. Every signed voucher is allowed and reported, which is the
     * behaviour before this check existed plus the evidence needed to configure it.
     *
     * @return the unconfigured binding
     */
    public static VoucherIssuerBinding unconfigured() {
        return new VoucherIssuerBinding(VoucherIssuerBindingMode.LOG, VoucherSignerTrustList.empty());
    }

    /**
     * Requires the signer of a voucher whose signature has already verified to be trusted for the
     * issuer it names, as far as the mode demands.
     *
     * @param signedVoucher a voucher-carrying secret with a verified issuer signature
     * @throws CashuErrorException {@code voucher_signature_invalid} in {@code ENFORCE} mode when
     *                             the signer is not trusted for the voucher's issuer
     */
    public void requireTrustedSigner(@NonNull WellKnownSecret signedVoucher) throws CashuErrorException {
        if (mode == VoucherIssuerBindingMode.OFF || isTrusted(signedVoucher)) {
            return;
        }
        reportUntrusted(signedVoucher);
        if (mode == VoucherIssuerBindingMode.ENFORCE) {
            throw new CashuErrorException(CashuErrorCode.voucher_signature_invalid,
                    "Voucher was signed by a key the mint does not trust for its issuer");
        }
    }

    /** @return the configured mode */
    public VoucherIssuerBindingMode mode() {
        return mode;
    }

    private boolean isTrusted(WellKnownSecret signedVoucher) {
        return trustedSigners.trusts(VoucherMetadata.issuerId(signedVoucher),
                VoucherMetadata.issuerPublicKey(signedVoucher));
    }

    /**
     * Logs and counts an untrusted signer without writing a full key or issuer id anywhere.
     *
     * <p>The signer is a 12-character key prefix, enough to match against a candidate key when
     * provisioning. The issuer is a hash prefix: in this deployment it is usually a merchant's
     * public key, and the line only needs to group vouchers by issuer, not name one.
     */
    private void reportUntrusted(WellKnownSecret signedVoucher) {
        String signer = VoucherSignerKey.tryParse(VoucherMetadata.issuerPublicKey(signedVoucher))
                .map(VoucherSignerKey::preview)
                .orElse("malformed");
        log.warn("voucher_issuer_untrusted mode={} voucherId={} issuerHash={} signerPrefix={}",
                mode.label(), VoucherMetadata.voucherId(signedVoucher),
                hashPrefix(VoucherMetadata.issuerId(signedVoucher)), signer);
        MetricRecorders.voucher().issuerUntrusted(mode);
    }

    private static String hashPrefix(String value) {
        if (value == null) {
            return "none";
        }
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)))
                .substring(0, LOG_HASH_HEX_LENGTH);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException everyJvmHasIt) {
            throw new IllegalStateException("SHA-256 not available", everyJvmHasIt);
        }
    }
}
