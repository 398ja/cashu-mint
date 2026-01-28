package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

public class InvalidateProofTest {

    private Mint mint;

    @BeforeEach
    public void setUp() {
        this.mint = new Mint(UUID.randomUUID().toString());
    }


    /**
     * The `InvalidateProofsTask` API processes a list of `Proof` objects by storing them in a database and marking them as invalid. It uses the `DBMintVault` to retrieve the associated `Mint` entity and the `DBProofVault` to handle database operations for each proof. The `execute` method performs these operations and returns the processed list of proofs. It throws a `CashuErrorException` if any operation fails.
     * @throws CashuErrorException
     */
    @Test
    public void invalidateProof() throws CashuErrorException {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(sampleSignature());
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(256);
        proof.setKeySetId("00c4a3dade22f81b");

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(mint.getId())).thenReturn(new MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        Mockito.doNothing().when(proofVaultService).store(Mockito.any());
        Mockito.doNothing().when(proofVaultService).invalidate(Mockito.any());

        try (MockedStatic<ProofEntity> proofEntityMock = Mockito.mockStatic(ProofEntity.class)) {
            proofEntityMock.when(() -> ProofEntity.fromProof(Mockito.any(), Mockito.any()))
                    .thenAnswer(invocation -> new ProofEntity());

            InvalidateProofsTask<RandomStringSecret> task = new InvalidateProofsTask<>(mint, List.of(proof), mintVaultService, proofVaultService);
            task.execute();

            Mockito.verify(proofVaultService).store(Mockito.any());
            Mockito.verify(proofVaultService).invalidate(Mockito.any());
        }
    }

    /**
     * The `invalidateProofFailure` test case verifies the behavior of the `InvalidateProofsTask` when the `invalidate` method of the `DBProofVault` throws an exception. Here's a plain English explanation:
     *
     * 1. A `RSSProof` object is created and populated with test data, such as a signature, secret, amount, and key set ID.
     * 2. An `InvalidateProofsTask` is created with the `mint` object and a list containing the `RSSProof`.
     * 3. The proof vault is mocked to simulate specific behaviors:
     *    - The `store` method does nothing when called.
     *    - The `invalidate` method throws a runtime exception with the message "fail".
     * 4. The test asserts that calling the `execute` method of the task results in a `CashuErrorException` being thrown.
     * 5. This ensures that the task handles the failure of the `invalidate` method as expected.
     */
    @Test
    public void invalidateProofFailure() throws CashuErrorException {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(sampleSignature());
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(1);
        proof.setKeySetId("00c4a3dade22f81b");

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        Mockito.doNothing().when(proofVaultService).store(Mockito.any());
        Mockito.doThrow(new IllegalStateException("fail")).when(proofVaultService).invalidate(Mockito.any());
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(mint.getId())).thenReturn(new MintEntity());

        try (MockedStatic<ProofEntity> proofEntityMock = Mockito.mockStatic(ProofEntity.class)) {
            proofEntityMock.when(() -> ProofEntity.fromProof(Mockito.any(), Mockito.any()))
                    .thenAnswer(invocation -> new ProofEntity());

            InvalidateProofsTask<RandomStringSecret> task = new InvalidateProofsTask<>(mint, List.of(proof), mintVaultService, proofVaultService);

            assertThrows(CashuErrorException.class, task::execute);
        }
    }
}
