package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.util.VoucherKeyDerivation;
import xyz.tcheeric.cashu.mint.proto.util.VoucherMasterSecretConfig;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

/**
 * Spending condition for voucher proofs with arbitrary denominations.
 *
 * <p>Unlike regular Cashu tokens that use power-of-2 denominations with pre-stored keys,
 * voucher proofs can have any positive amount. This condition derives the verification
 * key on-the-fly using the same HMAC-SHA256 derivation used during minting.
 *
 * <p>This ensures that vouchers minted with arbitrary amounts (e.g., 33 sats) can be
 * verified and swapped, even though no pre-stored key exists for that amount.
 *
 * @param <T> the secret type
 */
@AllArgsConstructor
@Slf4j
public class VoucherSpendingCondition<T extends Secret> implements SpendingCondition<T> {

    private final ProofVaultService proofVaultService;

    public VoucherSpendingCondition() {
        this(new DefaultProofVaultService());
    }

    @Override
    public void verify(@NonNull Proof<T> proof) throws CashuErrorException {
        log.debug("Verifying voucher proof: amount={}", proof.getAmount());

        // Check if proof has been used already
        Secret secret = proof.getSecret();
        ProofEntity proofEntity;
        try {
            proofEntity = proofVaultService.retrieveProof(secret.toString());
        } catch (Exception e) {
            log.warn("Failed to retrieve proof for secret {}: {}", secret, e.getMessage(), e);
            proofEntity = null;
        }

        if (proofEntity != null) {
            log.error("verify_proof_already_used_error voucher_proof amount={}", proof.getAmount());
            ErrorResponse error = new ErrorResponse("verify_proof_already_used_error");
            throw new CashuErrorException(error.toJson());
        }

        log.debug("Voucher proof has not been used...");

        if (proof.getKeySetId() == null || proof.getKeySetId().isBlank()) {
            log.error("verify_proof_key_set_id_error");
            ErrorResponse error = new ErrorResponse("verify_proof_key_set_id_error");
            throw new CashuErrorException(error.toJson());
        }

        // Derive the private key dynamically for voucher amounts
        PrivateKey privateKey = deriveVoucherKey(proof.getAmount());
        if (privateKey == null) {
            log.error("voucher_key_derivation_failed amount={}", proof.getAmount());
            ErrorResponse error = new ErrorResponse("voucher_key_derivation_failed");
            throw new CashuErrorException(error.toJson());
        }

        // Verify the proof using BDHKE
        byte[] C = proof.getUnblindedSignature().getBytes();
        if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
            log.error("verify_proof_failed_error voucher_proof amount={}", proof.getAmount());
            ErrorResponse error = new ErrorResponse("verify_proof_failed_error");
            throw new CashuErrorException(error.toJson());
        }

        log.info("voucher_proof_verified amount={}", proof.getAmount());
    }

    /**
     * Derives the private key for a voucher amount using the same derivation
     * as used during minting in SignBlindedMessageTask.
     *
     * @param amount the voucher amount
     * @return the derived private key, or null if derivation fails
     */
    private PrivateKey deriveVoucherKey(int amount) {
        try {
            String masterSecret = VoucherMasterSecretConfig.getMasterSecret();
            if (masterSecret == null || masterSecret.isEmpty()) {
                log.error("Voucher master secret not configured");
                return null;
            }
            return VoucherKeyDerivation.deriveKeyForAmount(masterSecret, amount);
        } catch (Exception e) {
            log.error("Failed to derive voucher key for amount {}: {}", amount, e.getMessage(), e);
            return null;
        }
    }
}
