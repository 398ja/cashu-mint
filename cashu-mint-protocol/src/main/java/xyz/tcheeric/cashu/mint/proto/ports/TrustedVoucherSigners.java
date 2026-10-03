package xyz.tcheeric.cashu.mint.proto.ports;

/**
 * Answers whether a voucher's signing key is one the mint recognises for the issuer it names
 * (cashu-mint#527).
 *
 * <p>A voucher carries its own {@code issuer_pubkey}, and its signature is verified against that
 * key. On its own that proves only that <em>someone</em> signed: anybody can generate a keypair,
 * name any {@code issuerId}, and produce a signature that verifies. This port is the missing
 * link between the key and the issuer.
 *
 * <p>The issuer and the signer are deliberately allowed to differ. In the Imani deployment the
 * merchant is the {@code issuerId}, while the gateway that issues on the merchant's behalf signs
 * with its own identity key. So a key is trusted when it is either the key registered for that
 * issuer, or a signer trusted to sign for any issuer.
 */
public interface TrustedVoucherSigners {

    /**
     * Whether {@code signerPublicKey} may sign vouchers naming {@code issuerId}.
     *
     * @param issuerId        the issuer the voucher names; may be null for a voucher without one
     * @param signerPublicKey the voucher's {@code issuer_pubkey}, x-only or compressed hex
     * @return true when the key is registered for the issuer or is a trusted signer
     */
    boolean trusts(String issuerId, String signerPublicKey);

    /**
     * Whether nothing at all is trusted, so every signed voucher would be untrusted.
     *
     * @return true when no issuer key and no trusted signer is configured
     */
    boolean isEmpty();
}
