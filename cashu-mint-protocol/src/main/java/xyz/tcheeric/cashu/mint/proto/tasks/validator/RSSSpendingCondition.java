package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@AllArgsConstructor
@Slf4j
public class RSSSpendingCondition implements SpendingCondition<RandomStringSecret> {

    @Setter(AccessLevel.NONE)
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final ProofVaultService proofVaultService;

    public RSSSpendingCondition(@NonNull Mint mint,
                                @NonNull MintProtocolService mintProtocolService) {
        this(mint, mintProtocolService, new DefaultProofVaultService());
    }

    @Override
    public void verify(Proof<RandomStringSecret> proof) throws CashuErrorException {

        log.debug("Verify proof {}", proof);

        // Check if proof has been used already. Only STATE_SPENT is terminal —
        // PENDING means another in-flight operation is still resolving and
        // InvalidateProofsTask handles idempotent recovery downstream.
        Secret secret = proof.getSecret();
        ProofEntity proofEntity;
        try {
            proofEntity = proofVaultService.retrieveProof(secret.toString());
        } catch (Exception e) {
            // If the vault lookup fails (network/remote error), log and treat as not found so verification can proceed
            log.warn("Failed to retrieve proof for secret {}: {}", secret, e.getMessage(), e);
            proofEntity = null;
        }
        log.debug("Proof entity {}...", proofEntity);
        if (proofEntity != null && ProofEntity.STATE_SPENT.equalsIgnoreCase(proofEntity.getState())) {
            log.error("verify_proof_already_used_error state={}", proofEntity.getState());
            throw new CashuErrorException(CashuErrorCode.verify_proof_already_used_error);
        }

        if (proofEntity != null) {
            log.debug("Proof exists in non-terminal state {} — allowing through for idempotent retry",
                    proofEntity.getState());
        } else {
            log.debug("The proof has not yet been used...");
        }

        if (proof.getKeySetId() == null || proof.getKeySetId().isBlank()) {
            log.error("verify_proof_key_set_id_error");
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_id_error);
        }

        log.debug("The proof key set id is valid...");

        // Verify the proof
        PrivateKey privateKey = getPrivateKey(proof, mint);
        if (privateKey == null) {
            log.error("verify_proof_key_set_not_found");
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_not_found);
        }

        byte[] C = proof.getUnblindedSignature().getBytes();
        if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
            log.error("verify_proof_failed_error");
            throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error);
        }
    }

    private PrivateKey getPrivateKey(@NonNull Proof<RandomStringSecret> proof, @NonNull Mint mint) throws CashuErrorException {
        log.debug("Getting private key for {}", proof);
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }

}
