package xyz.tcheeric.cashu.mint.proto.voucher;

import java.util.Locale;

/**
 * How the mint treats a signed voucher whose signing key it does not recognise for the issuer
 * the voucher names (cashu-mint#527). Configured as {@code cashu.mint.voucher.issuer-binding}.
 *
 * <p>Three modes rather than a boolean, because switching straight to refusal would break every
 * voucher swap on a deployment whose keys are not provisioned yet, and none are today. The
 * intended rollout is {@link #LOG} first, to learn which keys actually sign in production, then
 * {@link #ENFORCE} once they are configured.
 */
public enum VoucherIssuerBindingMode {

    /** The binding is not checked at all. */
    OFF,

    /** An untrusted signer is allowed, but logged as {@code voucher_issuer_untrusted} and counted. */
    LOG,

    /** An untrusted signer is refused with {@code voucher_signature_invalid}. */
    ENFORCE;

    /**
     * Label value for metrics and logs: the lower-case name, matching the property value.
     *
     * @return the label
     */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
