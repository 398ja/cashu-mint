package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

public class ArchiveProofTaskTest {

    private RSSProof createProof() {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(Signature.fromString(MintProtocolUtil.createRandomBytes(33)));
        proof.setWitness(new Witness());
        proof.setKeySetId("ks1");
        return proof;
    }

    /**
     * This test case, `executeSuccess`, verifies the successful execution of the `ArchiveProofTask`. It ensures that:
     *
     * 1. The `MintProtocolUtil` utility methods correctly transform the `Mint` and `Proof` objects into their respective entities.
     * 2. A `DBProofVault` instance is constructed and its `archive` method is called without errors.
     * 3. The task returns the same `Proof` object that was passed to it.
     * 4. The `archive` method of the constructed `DBProofVault` instance is invoked exactly once.
     * @throws CashuErrorException
     */
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

    /**
     * The `executeFailure` test case verifies the behavior of the `ArchiveProofTask` when the `archive` method of the `DBProofVault` throws an exception. Here's a plain English description:
     *
     * 1. A `Mint` object and a `RSSProof` object are created as inputs for the task.
     * 2. A mocked `ProofEntity` is prepared.
     * 3. The `MintProtocolUtil` utility methods are mocked to return appropriate mock objects when called.
     * 4. The `DBProofVault` constructor is mocked to simulate an exception (`CashuErrorException`) being thrown when its `archive` method is called.
     * 5. An `ArchiveProofTask` is created with the `Mint` and `RSSProof` objects.
     * 6. The test asserts that calling the `execute` method of the task results in a `CashuErrorException` being thrown.
     * 7. The test ensures that the exception is handled as expected, confirming the task's failure behavior.
     */
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

