package xyz.tcheeric.cashu.mint.proto.spending;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.VoucherSpendingCondition;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

/**
 * Unit tests for VoucherSpendingCondition.
 * Verifies that voucher proofs are verified using standard keyset keys
 * plus additional voucher-specific validations (expiry, issuer signature).
 */
class VoucherSpendingConditionTest {

    private Mint mockMint;
    private MintProtocolService mockMintProtocolService;
    private ProofVaultService mockProofVaultService;
    private VoucherSpendingCondition<VoucherSecret> condition;

    @BeforeEach
    void setUp() {
        mockMint = Mockito.mock(Mint.class);
        mockMintProtocolService = Mockito.mock(MintProtocolService.class);
        mockProofVaultService = Mockito.mock(ProofVaultService.class);
        condition = new VoucherSpendingCondition<>(mockMint, mockMintProtocolService, mockProofVaultService);
    }

    /**
     * Creates a voucher proof for testing with power-of-2 amount.
     */
    private Proof<VoucherSecret> createVoucherProof(int amount, String keysetId) {
        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(amount);
        proof.setKeySetId(keysetId);

        // Create a basic VoucherSecret
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId("test-merchant")
                .unit("sat")
                .faceValue(1000L)
                .build();
        proof.setSecret(secret);
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

    /**
     * Creates a voucher proof with expiry set.
     */
    private Proof<VoucherSecret> createExpiredVoucherProof(int amount, String keysetId) {
        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(amount);
        proof.setKeySetId(keysetId);

        // Create an expired VoucherSecret
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId("test-merchant")
                .unit("sat")
                .faceValue(1000L)
                .expiresAt(1L) // Expired in the past
                .build();
        proof.setSecret(secret);
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

    /**
     * Verifies that a voucher proof with a power-of-2 amount
     * can be verified using standard keyset keys.
     */
    @Test
    void verify_PowerOf2Amount_SucceedsWithStandardKeysetKey() throws CashuErrorException {
        // Arrange: Create a voucher proof with amount 8 (power-of-2)
        int amount = 8;
        String keysetId = "00abc123def45678";
        Proof<VoucherSecret> proof = createVoucherProof(amount, keysetId);

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        // Mock: keyset key lookup returns a valid key
        PrivateKey mockKey = Mockito.mock(PrivateKey.class);
        Mockito.when(mockKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(mockKey);

        // Mock BDHKEUtils to return true for verification
        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);

            // Act & Assert: Should verify successfully
            assertDoesNotThrow(() -> condition.verify(proof));
        }
    }

    /**
     * Verifies that an expired voucher proof is rejected.
     */
    @Test
    void verify_ExpiredVoucher_ThrowsException() throws CashuErrorException {
        // Arrange: Create an expired voucher proof
        int amount = 8;
        String keysetId = "00abc123def45678";
        Proof<VoucherSecret> proof = createExpiredVoucherProof(amount, keysetId);

        // Act & Assert: Should throw due to expiry
        CashuErrorException exception = assertThrows(CashuErrorException.class, () -> condition.verify(proof));
        assertEquals("voucher_expired", exception.getErrorCode().name(),
                "Exception should indicate voucher_expired");
    }

    /**
     * Verifies that a voucher proof that was already spent (terminal state) is rejected.
     */
    @Test
    void verify_AlreadyUsedProof_ThrowsException() throws CashuErrorException {
        // Arrange
        int amount = 8;
        String keysetId = "00abc123def45678";
        Proof<VoucherSecret> proof = createVoucherProof(amount, keysetId);

        // Mock: proof in terminal STATE_SPENT
        ProofEntity spent = new ProofEntity();
        spent.setState(ProofEntity.STATE_SPENT);
        Mockito.when(mockProofVaultService.retrieveProof(anyString()))
                .thenReturn(spent);

        // Act & Assert
        CashuErrorException ex = assertThrows(CashuErrorException.class, () -> condition.verify(proof));
        assertEquals("verify_proof_already_used_error", ex.getErrorCode().name(),
                "Exception should indicate verify_proof_already_used_error");
    }

    /**
     * Verifies that a voucher proof in STATE_PENDING is NOT rejected by the
     * spending condition. PENDING means another in-flight operation has the
     * proof locked; InvalidateProofsTask handles idempotent recovery downstream.
     * Rejecting here would block legitimate retries (e.g. saga resumes after a
     * transient backend hiccup).
     */
    @Test
    void verify_PendingProof_DoesNotThrowAlreadyUsed() throws CashuErrorException {
        int amount = 8;
        String keysetId = "00abc123def45678";
        Proof<VoucherSecret> proof = createVoucherProof(amount, keysetId);

        ProofEntity pending = new ProofEntity();
        pending.setState(ProofEntity.STATE_PENDING);
        Mockito.when(mockProofVaultService.retrieveProof(anyString()))
                .thenReturn(pending);

        // The spending condition may still fail later for an unrelated reason
        // (no real keyset, no real BDHKE proof) — the assertion is that
        // whatever it throws is NOT verify_proof_already_used_error.
        try {
            condition.verify(proof);
        } catch (CashuErrorException ex) {
            assertTrue(!ex.getMessage().contains("verify_proof_already_used_error"),
                    "PENDING must not be treated as terminal already-used; got: " + ex.getMessage());
        }
    }

    /**
     * Verifies that a voucher proof with an invalid BDHKE signature is rejected.
     */
    @Test
    void verify_InvalidSignature_ThrowsException() throws CashuErrorException {
        // Arrange
        int amount = 8;
        String keysetId = "00abc123def45678";
        Proof<VoucherSecret> proof = createVoucherProof(amount, keysetId);

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        // Mock: keyset key lookup returns a valid key
        PrivateKey mockKey = Mockito.mock(PrivateKey.class);
        Mockito.when(mockKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(mockKey);

        // Mock BDHKEUtils to return false (invalid signature)
        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(false);

            // Act & Assert
            assertThrows(CashuErrorException.class, () -> condition.verify(proof));
        }
    }

    /**
     * Verifies that a voucher proof without a keyset ID is rejected.
     */
    @Test
    void verify_MissingKeysetId_ThrowsException() throws CashuErrorException {
        // Arrange
        Proof<VoucherSecret> proof = createVoucherProof(8, null); // null keyset ID

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        // Act & Assert
        assertThrows(CashuErrorException.class, () -> condition.verify(proof));
    }

    /**
     * Verifies that a voucher proof with an empty keyset ID is rejected.
     */
    @Test
    void verify_EmptyKeysetId_ThrowsException() throws CashuErrorException {
        // Arrange
        Proof<VoucherSecret> proof = createVoucherProof(8, ""); // empty keyset ID

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        // Act & Assert
        assertThrows(CashuErrorException.class, () -> condition.verify(proof));
    }

    /**
     * Verifies that a voucher proof without a keyset key fails.
     */
    @Test
    void verify_KeyNotFound_ThrowsException() throws CashuErrorException {
        // Arrange
        int amount = 8;
        String keysetId = "00abc123def45678";
        Proof<VoucherSecret> proof = createVoucherProof(amount, keysetId);

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        // Mock: keyset key lookup returns null (key not found)
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(null);

        // Act & Assert
        assertThrows(CashuErrorException.class, () -> condition.verify(proof));
    }
}
