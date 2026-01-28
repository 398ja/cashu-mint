package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.web.client.HttpClientErrorException;
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

import static org.junit.jupiter.api.Assertions.*;
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

            CashuErrorException thrown = assertThrows(CashuErrorException.class, task::execute);
            // Verify the original exception is preserved as the cause
            assertNotNull(thrown.getCause());
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertEquals("fail", thrown.getCause().getMessage());
        }
    }

    /**
     * Verifies idempotent behavior when store() throws 409 Conflict and the proof is already spent.
     * This simulates a retry scenario where the same swap is submitted twice.
     */
    @Test
    public void invalidateProof_conflictAlreadySpent_idempotentSuccess() throws CashuErrorException {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(sampleSignature());
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(1);
        proof.setKeySetId("00c4a3dade22f81b");

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(mint.getId())).thenReturn(new MintEntity());

        // Simulate 409 Conflict on store
        Mockito.doThrow(HttpClientErrorException.Conflict.class)
                .when(proofVaultService).store(Mockito.any());

        // Return a proof that's already spent
        ProofEntity existingProof = new ProofEntity();
        existingProof.setSecret("0123456789abcdef0123456789abcdef");
        existingProof.setState(ProofEntity.STATE_SPENT);
        Mockito.when(proofVaultService.retrieveProof(Mockito.any())).thenReturn(existingProof);

        try (MockedStatic<ProofEntity> proofEntityMock = Mockito.mockStatic(ProofEntity.class)) {
            ProofEntity mockEntity = new ProofEntity();
            mockEntity.setSecret("0123456789abcdef0123456789abcdef");
            proofEntityMock.when(() -> ProofEntity.fromProof(Mockito.any(), Mockito.any()))
                    .thenReturn(mockEntity);

            InvalidateProofsTask<RandomStringSecret> task = new InvalidateProofsTask<>(mint, List.of(proof), mintVaultService, proofVaultService);

            // Should succeed without throwing - idempotent behavior
            List<?> result = task.execute();
            assertNotNull(result);

            // Verify invalidate was NOT called since proof is already spent
            Mockito.verify(proofVaultService, Mockito.never()).invalidate(Mockito.any());
        }
    }

    /**
     * Verifies behavior when store() throws 409 Conflict and the proof exists but is not yet spent.
     * The task should proceed to invalidate the existing proof.
     */
    @Test
    public void invalidateProof_conflictNotSpent_invalidatesExisting() throws CashuErrorException {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(sampleSignature());
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(1);
        proof.setKeySetId("00c4a3dade22f81b");

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(mint.getId())).thenReturn(new MintEntity());

        // Simulate 409 Conflict on store
        Mockito.doThrow(HttpClientErrorException.Conflict.class)
                .when(proofVaultService).store(Mockito.any());

        // Return a proof that exists but is NOT spent (e.g., PENDING state)
        ProofEntity existingProof = new ProofEntity();
        existingProof.setSecret("0123456789abcdef0123456789abcdef");
        existingProof.setState("PENDING");
        Mockito.when(proofVaultService.retrieveProof(Mockito.any())).thenReturn(existingProof);
        Mockito.doNothing().when(proofVaultService).invalidate(Mockito.any());

        try (MockedStatic<ProofEntity> proofEntityMock = Mockito.mockStatic(ProofEntity.class)) {
            ProofEntity mockEntity = new ProofEntity();
            mockEntity.setSecret("0123456789abcdef0123456789abcdef");
            proofEntityMock.when(() -> ProofEntity.fromProof(Mockito.any(), Mockito.any()))
                    .thenReturn(mockEntity);

            InvalidateProofsTask<RandomStringSecret> task = new InvalidateProofsTask<>(mint, List.of(proof), mintVaultService, proofVaultService);

            // Should succeed
            List<?> result = task.execute();
            assertNotNull(result);

            // Verify invalidate WAS called on the existing proof
            Mockito.verify(proofVaultService).invalidate(existingProof);
        }
    }

    /**
     * Verifies behavior when store() throws 409 Conflict but retrieveProof() returns null.
     * This can happen in race conditions. The task should treat this as idempotent success.
     */
    @Test
    public void invalidateProof_conflictProofNotFound_treatedAsIdempotent() throws CashuErrorException {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(sampleSignature());
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(1);
        proof.setKeySetId("00c4a3dade22f81b");

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(mint.getId())).thenReturn(new MintEntity());

        // Simulate 409 Conflict on store
        Mockito.doThrow(HttpClientErrorException.Conflict.class)
                .when(proofVaultService).store(Mockito.any());

        // retrieveProof returns null (race condition scenario)
        Mockito.when(proofVaultService.retrieveProof(Mockito.any())).thenReturn(null);

        try (MockedStatic<ProofEntity> proofEntityMock = Mockito.mockStatic(ProofEntity.class)) {
            ProofEntity mockEntity = new ProofEntity();
            mockEntity.setSecret("0123456789abcdef0123456789abcdef");
            proofEntityMock.when(() -> ProofEntity.fromProof(Mockito.any(), Mockito.any()))
                    .thenReturn(mockEntity);

            InvalidateProofsTask<RandomStringSecret> task = new InvalidateProofsTask<>(mint, List.of(proof), mintVaultService, proofVaultService);

            // Should succeed without throwing - treated as idempotent
            List<?> result = task.execute();
            assertNotNull(result);

            // Verify invalidate was NOT called
            Mockito.verify(proofVaultService, Mockito.never()).invalidate(Mockito.any());
        }
    }

    /**
     * Verifies that CashuErrorException is re-thrown as-is without wrapping.
     */
    @Test
    public void invalidateProofFailure_cashuErrorException_rethrown() throws CashuErrorException {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(sampleSignature());
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(1);
        proof.setKeySetId("00c4a3dade22f81b");

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        CashuErrorException originalException = new CashuErrorException("original_error");
        Mockito.doThrow(originalException).when(proofVaultService).store(Mockito.any());
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(mint.getId())).thenReturn(new MintEntity());

        try (MockedStatic<ProofEntity> proofEntityMock = Mockito.mockStatic(ProofEntity.class)) {
            proofEntityMock.when(() -> ProofEntity.fromProof(Mockito.any(), Mockito.any()))
                    .thenAnswer(invocation -> new ProofEntity());

            InvalidateProofsTask<RandomStringSecret> task = new InvalidateProofsTask<>(mint, List.of(proof), mintVaultService, proofVaultService);

            CashuErrorException thrown = assertThrows(CashuErrorException.class, task::execute);
            // Should be the exact same exception, not wrapped
            assertSame(originalException, thrown);
        }
    }
}
