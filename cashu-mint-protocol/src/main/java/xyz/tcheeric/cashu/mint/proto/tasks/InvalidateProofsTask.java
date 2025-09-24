package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.List;

@Slf4j
public class InvalidateProofsTask<T extends Secret> implements Task<List<Proof<T>>> {

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
    public List<Proof<T>> execute() throws CashuErrorException {
        var mintEntity = mintVaultService.retrieveMint(mint.getId());
        for (Proof<T> proof : proofs) {

            ProofEntity proofEntity = ProofEntity.fromProof(proof, mintEntity);

            proofVaultService.store(proofEntity);
            proofVaultService.invalidate(proofEntity);
        }

        return proofs;
    }
}
