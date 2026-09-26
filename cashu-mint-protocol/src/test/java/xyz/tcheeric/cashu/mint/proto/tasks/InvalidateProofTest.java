package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Mockito.when(proofVaultService.retrieveProofByY(Mockito.any())).thenReturn(existingProof);

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
        Mockito.when(proofVaultService.retrieveProofByY(Mockito.any())).thenReturn(existingProof);
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
        Mockito.when(proofVaultService.retrieveProofByY(Mockito.any())).thenReturn(null);

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
     * A 409 recovery must invalidate the row that caused the conflict, even though the only
     * identifier the task holds at that point is the curve point Y the row is stored under.
     *
     * <p>Unlike the three Mockito-stubbed conflict tests above, which answer the lookup with
     * {@code Mockito.any()} and so cannot tell a hashed key from an unhashed one, this test backs
     * the task with a vault that is keyed on the real Y and hashes its input in
     * {@code retrieveProof} exactly as {@code DefaultProofVaultService} does. A recovery that
     * passes the already-hashed Y to {@code retrieveProof} therefore hashes it a second time,
     * misses a row that is genuinely there, and silently returns without invalidating it. The
     * assertion is on that outcome, the stored row's final state, not on which vault method the
     * task chose to call.
     *
     * <p>This is not the not-found case covered by
     * {@code invalidateProof_conflictProofNotFound_treatedAsIdempotent}: here the vault does hold
     * the row under its Y, so a correct lookup finds it and only a double-hashing lookup does not.
     */
    @Test
    public void invalidateProof_conflictOnRowStoredUnderY_invalidatesThatRow() throws CashuErrorException {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(sampleSignature());
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(1);
        proof.setKeySetId("00c4a3dade22f81b");

        MintEntity mintEntity = new MintEntity();
        mintEntity.setId(UUID.fromString(mint.getId()));
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(mint.getId())).thenReturn(mintEntity);

        // The row a retried swap collides with: already recorded under this proof's Y, and still
        // unspent because the first attempt stored it and then failed before invalidating it.
        String storedY = SpentProofKey.issuanceKey(proof.getSecret().toString());
        ProofEntity unspentRow = new ProofEntity();
        unspentRow.setSecret(storedY);
        unspentRow.setState(ProofEntity.STATE_UNSPENT);
        unspentRow.setMint(mintEntity);
        VaultKeyedOnY vault = new VaultKeyedOnY();
        vault.seed(storedY, unspentRow);

        InvalidateProofsTask<RandomStringSecret> task =
                new InvalidateProofsTask<>(mint, List.of(proof), mintVaultService, vault);
        task.execute();

        assertEquals(ProofEntity.STATE_SPENT, unspentRow.getState(),
                "the row that caused the 409 is still unspent, so the retried swap reported "
                        + "success while leaving its input spendable");
        assertEquals(List.of(storedY), vault.invalidatedKeys(),
                "invalidation must land on the row stored under Y, not on a second row");
    }

    /**
     * A vault that stores proofs under the curve point Y, the way the real one does.
     *
     * <p>Its whole purpose is that {@link #retrieveProofByY} and {@link #retrieveProof} are not
     * interchangeable: the first takes a key, the second takes a secret and hashes it. Handing an
     * already-hashed Y to the second one therefore misses, which is precisely the failure a
     * {@code Mockito.any()} stub cannot express. {@code store} rejects a duplicate key with the
     * 409 the vault's {@code (mint_id, secret)} uniqueness constraint produces.
     */
    private static final class VaultKeyedOnY implements ProofVaultService {

        private final Map<String, ProofEntity> rowsByY = new HashMap<>();
        private final List<String> invalidatedKeys = new ArrayList<>();

        void seed(String y, ProofEntity row) {
            rowsByY.put(y, row);
        }

        List<String> invalidatedKeys() {
            return List.copyOf(invalidatedKeys);
        }

        @Override
        public void store(ProofEntity proofEntity) {
            if (rowsByY.containsKey(proofEntity.getSecret())) {
                throw HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
            }
            rowsByY.put(proofEntity.getSecret(), proofEntity);
        }

        @Override
        public void invalidate(ProofEntity proofEntity) throws CashuErrorException {
            ProofEntity stored = rowsByY.get(proofEntity.getSecret());
            if (stored == null) {
                throw new CashuErrorException("invalidate_on_unstored_row");
            }
            stored.setState(ProofEntity.STATE_SPENT);
            invalidatedKeys.add(proofEntity.getSecret());
        }

        @Override
        public void archive(ProofEntity proofEntity) {
            rowsByY.remove(proofEntity.getSecret());
        }

        @Override
        public void storePending(ProofEntity proofEntity) {
            proofEntity.setState(ProofEntity.STATE_PENDING);
            rowsByY.put(proofEntity.getSecret(), proofEntity);
        }

        @Override
        public ProofEntity retrieveProof(UUID mintId, String secret) {
            return firstRowUnderAnyKeyFor(secret);
        }

        @Override
        public ProofEntity retrieveProofByY(String yHex) {
            return rowsByY.get(yHex);
        }

        @Override
        public String storageKeyFor(UUID mintId, String secret) {
            for (String key : SpentProofKey.lookupKeys(secret)) {
                if (rowsByY.containsKey(key)) {
                    return key;
                }
            }
            return SpentProofKey.issuanceKey(secret);
        }

        private ProofEntity firstRowUnderAnyKeyFor(String secret) {
            for (String key : SpentProofKey.lookupKeys(secret)) {
                ProofEntity stored = rowsByY.get(key);
                if (stored != null) {
                    return stored;
                }
            }
            return null;
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
