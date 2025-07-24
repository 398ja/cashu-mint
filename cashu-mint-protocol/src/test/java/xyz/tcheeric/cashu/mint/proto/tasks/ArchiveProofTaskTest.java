package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

public class ArchiveProofTaskTest {

    private RSSProof createProof() {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(Signature.fromString("00"));
        proof.setWitness(new Witness());
        proof.setKeySetId("ks1");
        return proof;
    }

    @Test
    public void executeSuccess() throws CashuErrorException {
        Mint mint = new Mint();
        RSSProof proof = createProof();

        ProofEntity proofEntity = Mockito.mock(ProofEntity.class);

        try (MockedStatic<MintProtocolUtil> util = Mockito.mockStatic(MintProtocolUtil.class);
             MockedConstruction<DBProofVault> cons = Mockito.mockConstruction(DBProofVault.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).archive())) {

            util.when(() -> MintProtocolUtil.toMintEntity(any(Mint.class))).thenReturn(Mockito.mock(MintEntity.class));
            util.when(() -> MintProtocolUtil.toProofEntity(any(), any())).thenReturn(proofEntity);

            ArchiveProofTask<RandomStringSecret> task = new ArchiveProofTask<>(mint, proof);
            RSSProof result = (RSSProof) task.execute();

            assertSame(proof, result);
            verify(cons.constructed().get(0)).archive();
        }
    }

    @Test
    public void executeFailure() {
        Mint mint = new Mint();
        RSSProof proof = createProof();

        ProofEntity proofEntity = Mockito.mock(ProofEntity.class);

        try (MockedStatic<MintProtocolUtil> util = Mockito.mockStatic(MintProtocolUtil.class);
             MockedConstruction<DBProofVault> cons = Mockito.mockConstruction(DBProofVault.class,
                     (mock, ctx) -> Mockito.doThrow(new CashuErrorException("fail")).when(mock).archive())) {

            util.when(() -> MintProtocolUtil.toMintEntity(any(Mint.class))).thenReturn(Mockito.mock(MintEntity.class));
            util.when(() -> MintProtocolUtil.toProofEntity(any(), any())).thenReturn(proofEntity);

            ArchiveProofTask<RandomStringSecret> task = new ArchiveProofTask<>(mint, proof);
            assertThrows(CashuErrorException.class, task::execute);
        }
    }
}

