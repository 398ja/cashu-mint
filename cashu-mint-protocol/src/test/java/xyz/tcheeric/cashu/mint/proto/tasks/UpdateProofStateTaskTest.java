package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

public class UpdateProofStateTaskTest {

    private RSSProof createProof() {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        proof.setWitness(new Witness());
        proof.setKeySetId("ks1");
        return proof;
    }

    /**
     * Verifies that the task converts a proof to an entity and stores it as pending without error.
     */
    @Test
    public void executeSuccess() throws CashuErrorException {
        Mint mint = new Mint();
        RSSProof proof = createProof();
        ProofEntity proofEntity = Mockito.mock(ProofEntity.class);

        try (MockedStatic<MintProtocolUtil> util = Mockito.mockStatic(MintProtocolUtil.class);
             MockedConstruction<DBProofVault> cons = Mockito.mockConstruction(DBProofVault.class)) {

            util.when(() -> MintProtocolUtil.toMintEntity(any(Mint.class))).thenReturn(Mockito.mock(MintEntity.class));
            util.when(() -> MintProtocolUtil.toProofEntity(any(), any())).thenReturn(proofEntity);

            UpdateProofStateTask<RandomStringSecret> task = new UpdateProofStateTask<>(mint, proof);
            Boolean result = task.execute();
            assertTrue(result);
            Mockito.verify(cons.constructed().get(0)).storePending(proofEntity);
        }
    }
}
