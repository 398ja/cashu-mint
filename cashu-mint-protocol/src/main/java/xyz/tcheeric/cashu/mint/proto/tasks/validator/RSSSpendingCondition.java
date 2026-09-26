package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.log.SecretLogId;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

/**
 * Spending condition for plain random-string proofs.
 *
 * <p>Every collaborator is required: a condition without a mint cannot run the per-mint
 * double-spend check, so that state is unconstructible (cashu-mint#488).
 */
@AllArgsConstructor
@Slf4j
public class RSSSpendingCondition implements SpendingCondition<RandomStringSecret> {

    @Setter(AccessLevel.NONE)
    @NonNull
    private final Mint mint;
    @NonNull
    private final MintProtocolService mintProtocolService;
    @NonNull
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
        // Resolved before the try: the catch below deliberately treats a vault failure as
        // "no proof found" so verification can proceed, and a missing mint must not be absorbed
        // by that. Without a mint there is no double-spend check at all.
        UUID mintId = requireMintId();
        try {
            proofEntity = proofVaultService.retrieveProof(mintId, secret.toString());
        } catch (CashuErrorException | RestClientException vaultUnavailable) {
            // Only a vault outage is absorbed, so that an outage does not block verification.
            // Anything else is a programming error on the one path where hiding it is least
            // acceptable: an NPE caught here once disabled the double-spend check (#486, #488).
            log.warn("rss_proof_lookup_failed secret={} reason={}",
                    SecretLogId.of(secret.toString()), vaultUnavailable.toString());
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
        PrivateKey privateKey = getPrivateKey(proof);
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

    private PrivateKey getPrivateKey(@NonNull Proof<RandomStringSecret> proof) throws CashuErrorException {
        log.debug("Getting private key for {}", proof);
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }

    /**
     * The mint whose proof table the double-spend check must consult.
     *
     * <p>Fails rather than returning null. The constructors reject a null mint, so this guards a
     * mint without an id, and stays as defence in depth: a proof lookup without a mint cannot
     * answer "has this been spent here", because an unscoped lookup could read another mint's row
     * and skipping the lookup would let an already-spent proof verify.
     */
    private UUID requireMintId() throws CashuErrorException {
        if (mint.getId() == null) {
            log.error("verify_proof_no_mint_error rss_proof: cannot check for a double spend "
                    + "without a mint, refusing to verify");
            throw new CashuErrorException(
                    "Cannot verify a proof without a mint: the double-spend check is scoped per "
                            + "mint and cannot be skipped");
        }
        return UUID.fromString(mint.getId());
    }
}
