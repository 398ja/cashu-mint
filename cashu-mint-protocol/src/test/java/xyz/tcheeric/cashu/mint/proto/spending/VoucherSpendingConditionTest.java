package xyz.tcheeric.cashu.mint.proto.spending;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.VoucherSpendingCondition;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

/**
 * Unit tests for VoucherSpendingCondition with dynamic key derivation.
 * Verifies that voucher proofs with arbitrary amounts can be verified
 * using the same key derivation used during minting.
 */
class VoucherSpendingConditionTest {

    private Mint mockMint;
    private MintProtocolService mockMintProtocolService;
    private ProofVaultService mockProofVaultService;
    private VoucherSpendingCondition<RandomStringSecret> condition;

    @BeforeEach
    void setUp() {
        mockMint = Mockito.mock(Mint.class);
        mockMintProtocolService = Mockito.mock(MintProtocolService.class);
        mockProofVaultService = Mockito.mock(ProofVaultService.class);
        condition = new VoucherSpendingCondition<>(mockMint, mockMintProtocolService, mockProofVaultService);

        // Ensure master secret is set for tests
        System.setProperty("voucher.master.secret",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("voucher.master.secret");
    }

    /**
     * Creates a proof for testing with arbitrary amount (non-power-of-2).
     */
    private Proof<RandomStringSecret> createVoucherProof(int amount, String keysetId) {
        Proof<RandomStringSecret> proof = new Proof<>();
        proof.setAmount(amount);
        proof.setKeySetId(keysetId);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

    /**
     * Verifies that a voucher proof with an arbitrary (non-power-of-2) amount
     * can be verified using dynamic key derivation.
     */
    @Test
    void verify_ArbitraryAmount_SucceedsWithDynamicKeyDerivation() throws CashuErrorException {
        // Arrange: Create a voucher proof with amount 33 (non-power-of-2)
        int arbitraryAmount = 33;
        String keysetId = "voucher-keyset-001";
        Proof<RandomStringSecret> proof = createVoucherProof(arbitraryAmount, keysetId);

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        // Mock: return a valid private key
        PrivateKey mockPrivateKey = Mockito.mock(PrivateKey.class);
        Mockito.when(mockPrivateKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(mockPrivateKey);

        // Mock BDHKEUtils to return true for verification
        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);

            // Act & Assert: Should verify successfully
            assertDoesNotThrow(() -> condition.verify(proof));
        }
    }

    /**
     * Verifies that a voucher proof with another arbitrary amount (67) works.
     * This tests the dynamic key derivation for different amounts.
     */
    @Test
    void verify_AnotherArbitraryAmount_SucceedsWithDynamicKeyDerivation() throws CashuErrorException {
        // Arrange: Create a voucher proof with amount 67 (non-power-of-2)
        int arbitraryAmount = 67;
        String keysetId = "voucher-keyset-002";
        Proof<RandomStringSecret> proof = createVoucherProof(arbitraryAmount, keysetId);

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        // Mock: return a valid private key
        PrivateKey mockPrivateKey = Mockito.mock(PrivateKey.class);
        Mockito.when(mockPrivateKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(mockPrivateKey);

        // Mock BDHKEUtils to return true for verification
        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);

            // Act & Assert: Should verify successfully
            assertDoesNotThrow(() -> condition.verify(proof));
        }
    }

    /**
     * Verifies that a voucher proof that was already used is rejected.
     */
    @Test
    void verify_AlreadyUsedProof_ThrowsException() throws CashuErrorException {
        // Arrange
        int amount = 67;
        String keysetId = "voucher-keyset-001";
        Proof<RandomStringSecret> proof = createVoucherProof(amount, keysetId);

        // Mock: proof already used
        Mockito.when(mockProofVaultService.retrieveProof(anyString()))
                .thenReturn(new ProofEntity());

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);

            // Act & Assert
            assertThrows(CashuErrorException.class, () -> condition.verify(proof));
        }
    }

    /**
     * Verifies that a voucher proof with an invalid signature is rejected.
     */
    @Test
    void verify_InvalidSignature_ThrowsException() throws CashuErrorException {
        // Arrange
        int amount = 42;
        String keysetId = "voucher-keyset-001";
        Proof<RandomStringSecret> proof = createVoucherProof(amount, keysetId);

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

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
        Proof<RandomStringSecret> proof = createVoucherProof(50, null); // null keyset ID

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            // Act & Assert
            assertThrows(CashuErrorException.class, () -> condition.verify(proof));
        }
    }

    /**
     * Verifies that a voucher proof with an empty keyset ID is rejected.
     */
    @Test
    void verify_EmptyKeysetId_ThrowsException() throws CashuErrorException {
        // Arrange
        Proof<RandomStringSecret> proof = createVoucherProof(50, ""); // empty keyset ID

        // Mock: proof not yet used
        Mockito.when(mockProofVaultService.retrieveProof(anyString())).thenReturn(null);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            // Act & Assert
            assertThrows(CashuErrorException.class, () -> condition.verify(proof));
        }
    }
}
