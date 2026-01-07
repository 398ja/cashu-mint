package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

/**
 * Spending condition for voucher proofs.
 *
 * <p>Voucher proofs use the same keyset-based verification as regular proofs.
 * The voucher secret (NUT-10 format) is used for hash_to_curve computation,
 * but the signature is verified using the standard keyset private key.
 *
 * <p>This ensures compatibility with the standard swap flow where:
 * <ol>
 *   <li>Issuer swaps regular proofs for proofs with voucher secrets</li>
 *   <li>Mint signs new proofs using keyset keys (not derived keys)</li>
 *   <li>Redeemer swaps voucher proofs, verified with same keyset keys</li>
 * </ol>
 *
 * @param <T> the secret type
 */
@Slf4j
public class VoucherSpendingCondition<T extends Secret> implements SpendingCondition<T> {

    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final ProofVaultService proofVaultService;

    public VoucherSpendingCondition() {
        this(null, null, new DefaultProofVaultService());
    }

    public VoucherSpendingCondition(@NonNull Mint mint, @NonNull MintProtocolService mintProtocolService) {
        this(mint, mintProtocolService, new DefaultProofVaultService());
    }

    public VoucherSpendingCondition(Mint mint, MintProtocolService mintProtocolService,
                                    ProofVaultService proofVaultService) {
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
        this.proofVaultService = proofVaultService;
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

        // Get the private key from keyset (same as RSSSpendingCondition)
        PrivateKey privateKey = getPrivateKey(proof);
        if (privateKey == null) {
            log.error("verify_proof_key_set_not_found voucher_proof amount={} keysetId={}",
                    proof.getAmount(), proof.getKeySetId());
            ErrorResponse error = new ErrorResponse("verify_proof_key_set_not_found");
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
     * Gets the private key for verification from the keyset.
     *
     * @param proof the proof to verify
     * @return the private key, or null if not found
     */
    private PrivateKey getPrivateKey(@NonNull Proof<T> proof) throws CashuErrorException {
        if (mint == null || mintProtocolService == null) {
            log.error("VoucherSpendingCondition requires mint and mintProtocolService for keyset lookup");
            return null;
        }
        log.debug("Getting private key for voucher proof: keysetId={} amount={}",
                proof.getKeySetId(), proof.getAmount());
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }
}
