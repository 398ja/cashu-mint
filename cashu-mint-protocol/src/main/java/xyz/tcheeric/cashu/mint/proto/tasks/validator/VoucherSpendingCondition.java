package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;

/**
 * Spending condition for voucher proofs.
 *
 * <p>This condition verifies voucher proofs using the standard Cashu BDHKE verification
 * (same as {@link RSSSpendingCondition}) plus additional voucher-specific validations:
 * <ul>
 *   <li>Expiry check: Rejects expired vouchers</li>
 *   <li>Issuer signature: Verifies the issuer's Schnorr signature on the voucher metadata</li>
 * </ul>
 *
 * <p>Unlike the previous implementation, this uses the standard keyset keys for BDHKE
 * verification. Voucher backing amounts are power-of-2 like regular Cashu tokens.
 * The voucher metadata (face value, issuer, etc.) is stored in the secret's NUT-10 tags
 * and does not affect the cryptographic key used for the proof.
 *
 * @param <T> the secret type (must be VoucherSecret)
 */
@AllArgsConstructor
@Slf4j
public class VoucherSpendingCondition<T extends Secret> implements SpendingCondition<T> {

    @Setter(AccessLevel.NONE)
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final ProofVaultService proofVaultService;

    public VoucherSpendingCondition(@NonNull Mint mint,
                                    @NonNull MintProtocolService mintProtocolService) {
        this(mint, mintProtocolService, new DefaultProofVaultService());
    }

    /**
     * Legacy constructor for backward compatibility.
     * @deprecated Use the constructor with Mint and MintProtocolService instead.
     */
    @Deprecated
    public VoucherSpendingCondition() {
        this.mint = null;
        this.mintProtocolService = null;
        this.proofVaultService = new DefaultProofVaultService();
    }

    @Override
    public void verify(@NonNull Proof<T> proof) throws CashuErrorException {
        log.debug("Verifying voucher proof: amount={}", proof.getAmount());

        Secret secret = proof.getSecret();

        // Extract VoucherSecret for voucher-specific validations
        VoucherSecret voucherSecret = extractVoucherSecret(secret);

        // 1. Validate voucher expiry
        if (voucherSecret != null && voucherSecret.isExpired()) {
            log.error("voucher_expired voucherId={} expiresAt={}",
                    voucherSecret.getVoucherId(), voucherSecret.getExpiresAt());
            ErrorResponse error = new ErrorResponse("voucher_expired",
                    "Voucher has expired and cannot be redeemed");
            throw new CashuErrorException(error.toJson());
        }

        // 2. Validate issuer signature
        if (voucherSecret != null && voucherSecret.isSigned()) {
            if (!VoucherSignatureService.verify(voucherSecret)) {
                log.error("voucher_signature_invalid voucherId={} issuerPubkey={}",
                        voucherSecret.getVoucherId(), voucherSecret.getIssuerPublicKey());
                ErrorResponse error = new ErrorResponse("voucher_signature_invalid",
                        "Voucher issuer signature verification failed");
                throw new CashuErrorException(error.toJson());
            }
            log.debug("Voucher issuer signature verified: voucherId={}", voucherSecret.getVoucherId());
        }

        // 3. Check if proof has been used already (double-spend prevention).
        //    Only STATE_SPENT is terminal — PENDING means another in-flight
        //    operation is still resolving and InvalidateProofsTask will
        //    re-invalidate idempotently from PENDING when the swap actually
        //    commits. Throwing here on PENDING would block legitimate retries
        //    and ignores the state machine that the storage path already
        //    encodes (see InvalidateProofsTask.storeAndInvalidateIdempotent).
        ProofEntity proofEntity;
        try {
            proofEntity = proofVaultService.retrieveProof(secret.toString());
        } catch (Exception e) {
            log.warn("Failed to retrieve proof for secret {}: {}", secret, e.getMessage(), e);
            proofEntity = null;
        }

        if (proofEntity != null && ProofEntity.STATE_SPENT.equalsIgnoreCase(proofEntity.getState())) {
            log.error("verify_proof_already_used_error voucher_proof amount={} state={}",
                    proof.getAmount(), proofEntity.getState());
            ErrorResponse error = new ErrorResponse("verify_proof_already_used_error");
            throw new CashuErrorException(error.toJson());
        }

        if (proofEntity != null) {
            log.debug("Voucher proof exists in non-terminal state {} — allowing through for idempotent retry",
                    proofEntity.getState());
        } else {
            log.debug("Voucher proof has not been used...");
        }

        // 4. Validate keyset ID
        if (proof.getKeySetId() == null || proof.getKeySetId().isBlank()) {
            log.error("verify_proof_key_set_id_error");
            ErrorResponse error = new ErrorResponse("verify_proof_key_set_id_error");
            throw new CashuErrorException(error.toJson());
        }

        log.debug("Voucher proof keyset id is valid...");

        // 5. Get private key from keyset (standard power-of-2 key lookup)
        PrivateKey privateKey = getPrivateKey(proof, mint);
        if (privateKey == null) {
            log.error("verify_proof_key_set_not_found amount={}", proof.getAmount());
            ErrorResponse error = new ErrorResponse("verify_proof_key_set_not_found");
            throw new CashuErrorException(error.toJson());
        }

        // 6. Verify BDHKE signature (same as RSSSpendingCondition)
        byte[] C = proof.getUnblindedSignature().getBytes();
        if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
            log.error("verify_proof_failed_error voucher_proof amount={}", proof.getAmount());
            ErrorResponse error = new ErrorResponse("verify_proof_failed_error");
            throw new CashuErrorException(error.toJson());
        }

        log.info("voucher_proof_verified amount={} voucherId={}",
                proof.getAmount(),
                voucherSecret != null ? voucherSecret.getVoucherId() : "unknown");
    }

    /**
     * Extracts VoucherSecret from a generic Secret.
     *
     * @param secret the secret to extract from
     * @return the VoucherSecret if it's a voucher, null otherwise
     */
    private VoucherSecret extractVoucherSecret(Secret secret) {
        if (secret instanceof VoucherSecret) {
            return (VoucherSecret) secret;
        }
        // Try to detect voucher from string representation if needed
        // This handles cases where the secret was deserialized as a generic type
        return null;
    }

    /**
     * Gets the private key for the proof amount using standard keyset lookup.
     *
     * @param proof the proof containing amount and keyset ID
     * @param mint the mint instance
     * @return the private key for the amount, or null if not found
     */
    private PrivateKey getPrivateKey(@NonNull Proof<T> proof, Mint mint) throws CashuErrorException {
        if (mintProtocolService == null || mint == null) {
            log.error("VoucherSpendingCondition not properly initialized - missing mint or protocol service");
            return null;
        }
        log.debug("Getting private key for voucher proof amount={}", proof.getAmount());
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }
}
