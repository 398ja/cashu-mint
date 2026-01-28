package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.List;

@Slf4j
public class InvalidateProofsTask<T extends Secret> extends InstrumentedTask<List<Proof<T>>> {

    private final Mint mint;
    private final List<Proof<T>> proofs;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;

    public InvalidateProofsTask(@NonNull Mint mint,
                                @NonNull List<Proof<T>> proofs) {
        this(mint, proofs, new DefaultMintVaultService(), new DefaultProofVaultService());
    }

    public InvalidateProofsTask(@NonNull Mint mint,
                                @NonNull List<Proof<T>> proofs,
                                @NonNull MintVaultService mintVaultService,
                                @NonNull ProofVaultService proofVaultService) {
        this.mint = mint;
        this.proofs = proofs;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
    }

    @Override
    protected List<Proof<T>> doExecute() throws CashuErrorException {
        var mintEntity = mintVaultService.retrieveMint(mint.getId());
        for (Proof<T> proof : proofs) {
            // Build a minimal, database-ready ProofEntity without invoking heavy cryptographic conversions
            // that expect specific key encodings. This avoids errors when secrets are 32-byte values.
            ProofEntity proofEntity = MintProtocolUtil.toProofEntity(proof, mintEntity);
            storeAndInvalidateIdempotent(proof, proofEntity);
        }

        return proofs;
    }

    /**
     * Stores and invalidates a proof idempotently.
     * If the proof already exists (409 Conflict), checks if it's already spent.
     * If already spent, treats it as success (idempotent behavior for retries).
     * If not spent, invalidates it.
     */
    private void storeAndInvalidateIdempotent(Proof<T> proof, ProofEntity proofEntity) throws CashuErrorException {
        try {
            proofVaultService.store(proofEntity);
            log.debug("invalidate_proofs_task proof_stored secret={}",
                    proofEntity.getSecret() != null ? proofEntity.getSecret().substring(0, 16) + "..." : "null");
            proofVaultService.invalidate(proofEntity);
        } catch (HttpClientErrorException.Conflict e) {
            // 409 Conflict means proof already exists - this is expected for retried swaps
            // Check if it's already spent (idempotent case) or needs invalidation
            log.info("invalidate_proofs_task proof_already_exists checking_state secret={}",
                    proofEntity.getSecret() != null ? proofEntity.getSecret().substring(0, 16) + "..." : "null");

            // Use the same secret that was used for storage (proofEntity.getSecret() is already the Y point)
            ProofEntity existingProof = proofVaultService.retrieveProof(proofEntity.getSecret());

            if (existingProof == null) {
                // 409 conflict but can't find proof - likely a race condition or different lookup key
                // Treat as already handled (idempotent) since the store conflicted
                log.warn("invalidate_proofs_task proof_not_found_after_409_treating_as_spent secret={}",
                        proofEntity.getSecret() != null ? proofEntity.getSecret().substring(0, 16) + "..." : "null");
                // Continue without throwing - the proof exists (hence the 409) so treat as idempotent success
                return;
            }

            if (ProofEntity.STATE_SPENT.equalsIgnoreCase(existingProof.getState())) {
                // Already spent - this is a successful retry, treat as idempotent success
                log.info("invalidate_proofs_task proof_already_spent_idempotent secret={}",
                        existingProof.getSecret() != null ? existingProof.getSecret().substring(0, 16) + "..." : "null");
            } else {
                // Exists but not yet invalidated - invalidate it now
                log.info("invalidate_proofs_task proof_exists_invalidating secret={} current_state={}",
                        existingProof.getSecret() != null ? existingProof.getSecret().substring(0, 16) + "..." : "null",
                        existingProof.getState());
                proofVaultService.invalidate(existingProof);
            }
        } catch (CashuErrorException e) {
            // Re-throw CashuErrorException as-is to preserve error details
            throw e;
        } catch (Exception e) {
            // Wrap other exceptions preserving the original cause for debugging
            CashuErrorException wrapped = new CashuErrorException(
                    "invalidate_proof_failed: " + e.getMessage());
            wrapped.initCause(e);
            throw wrapped;
        }
    }
}
