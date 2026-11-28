package xyz.tcheeric.cashu.mint.proto.spending;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.VoucherSpendingCondition;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

/**
 * Unit tests for VoucherSpendingCondition.
 * Verifies that voucher proofs are verified using standard keyset keys
 * plus additional voucher-specific validations (expiry, issuer signature).
 */
class VoucherSpendingConditionTest {

    /** The mint the double-spend lookup must be scoped to; asserted on, so it is named. */
    private static final String MINT_ID = "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae";

    private Mint mockMint;
    private MintProtocolService mockMintProtocolService;
    private ProofVaultService mockProofVaultService;
    private VoucherSpendingCondition<VoucherSecret> condition;

    @BeforeEach
    void setUp() {
        mockMint = Mockito.mock(Mint.class);
        // The double-spend check is scoped per mint, so the condition needs a real mint id.
        // An unstubbed mock returns null here, and the condition refuses to verify rather than
        // performing an unscoped lookup or skipping the check (cashu-vault#153).
        Mockito.when(mockMint.getId()).thenReturn(MINT_ID);
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
        Mockito.when(mockProofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);

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
     * Creates an <em>expired P2PK-locked</em> voucher proof.
     *
     * <p>The regression this guards: {@code P2PKVoucherSecret} is not a {@code VoucherSecret},
     * so the condition used to cast, get null, and skip every voucher check. An expired
     * P2PK_VOUCHER verified.
     */
    private Proof<P2PKVoucherSecret> createExpiredLockedVoucherProof(int amount, String keysetId) {
        Proof<P2PKVoucherSecret> proof = new Proof<>();
        proof.setAmount(amount);
        proof.setKeySetId(keysetId);

        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(
                "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"));
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId("test-merchant");
        secret.setUnit("sat");
        secret.setFaceValue(1000L);
        secret.setExpiresAt(1L); // 1970

        proof.setSecret(secret);
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

    /**
     * An expired P2PK-locked voucher must be rejected for the same reason an expired ordinary
     * one is.
     *
     * <p>Regression test. The voucher checks were guarded on a cast to {@code VoucherSecret}
     * succeeding; for this kind it never does, so expiry and the issuer signature were
     * silently skipped and only the lock was enforced. Half of a two-part condition passing
     * looks exactly like the whole thing passing.
     */
    @Test
    void verify_ExpiredP2PKVoucher_ThrowsException() {
        int amount = 8;
        String keysetId = "00abc123def45678";
        VoucherSpendingCondition<P2PKVoucherSecret> lockedCondition =
                new VoucherSpendingCondition<>(mockMint, mockMintProtocolService, mockProofVaultService);
        Proof<P2PKVoucherSecret> proof = createExpiredLockedVoucherProof(amount, keysetId);

        CashuErrorException exception =
                assertThrows(CashuErrorException.class, () -> lockedCondition.verify(proof));
        assertEquals("voucher_expired", exception.getErrorCode().name(),
                "An expired P2PK-locked voucher must be rejected, not silently skipped");
    }

    /**
     * A condition holding a mint that cannot supply an id must refuse to verify rather than
     * verifying without a double-spend check.
     *
     * <p>Regression test for the bug this suite exists for. The mint id was originally resolved
     * with {@code UUID.fromString(mint.getId())} <em>inside</em> the try that treats a vault
     * failure as "no proof found". An unstubbed mint mock returns a null id, so the resulting NPE
     * was caught and relabelled "proof not found", and every voucher proof passed the double-spend
     * check. The assertion that the vault was never consulted is what makes this a guard test: a
     * condition without a mint has no lookup it is entitled to perform.
     */
    @Test
    void verify_MintWithoutId_RefusesRatherThanSkippingTheDoubleSpendCheck() throws CashuErrorException {
        Proof<VoucherSecret> proof = createVoucherProof(8, "00abc123def45678");
        // An unstubbed mock returns null from getId(), which is exactly the shape that hid the bug.
        Mint mintWithoutId = Mockito.mock(Mint.class);
        VoucherSpendingCondition<VoucherSecret> conditionWithoutMintId =
                new VoucherSpendingCondition<>(mintWithoutId, mockMintProtocolService, mockProofVaultService);

        // Everything downstream is stubbed to succeed, so the only reason to fail is the guard.
        PrivateKey mockKey = Mockito.mock(PrivateKey.class);
        Mockito.when(mockKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(mockKey);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);

            CashuErrorException ex =
                    assertThrows(CashuErrorException.class, () -> conditionWithoutMintId.verify(proof),
                            "Without a mint there is no double-spend check, so verification must "
                                    + "not succeed");
            assertTrue(ex.getMessage().contains("without a mint"),
                    "The refusal must name its cause; got: " + ex.getMessage());
        }
        Mockito.verify(mockProofVaultService, never()).retrieveProof(any(), any(ProofSecret.class));
    }

    /**
     * A condition cannot be built without a mint, a protocol service or a vault (#488).
     *
     * <p>The deprecated no-argument constructor, which had no callers, set {@code mint} to null
     * and was the only way to reach an uninitialised condition. It is gone, and the all-arguments
     * constructor now rejects nulls too, so the state the double-spend guard caught is
     * unrepresentable.
     */
    @Test
    void aConditionCannotBeBuiltWithoutItsCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new VoucherSpendingCondition<VoucherSecret>(null, mockMintProtocolService, mockProofVaultService));
        assertThrows(NullPointerException.class,
                () -> new VoucherSpendingCondition<VoucherSecret>(mockMint, null, mockProofVaultService));
        assertThrows(NullPointerException.class,
                () -> new VoucherSpendingCondition<VoucherSecret>(mockMint, mockMintProtocolService, null));
        assertThrows(NullPointerException.class,
                () -> new VoucherSpendingCondition<VoucherSecret>(null, mockMintProtocolService));
    }

    /**
     * The uninitialised state has no way in: there is no public no-argument constructor to reach it.
     */
    @Test
    void thereIsNoNoArgumentConstructor() {
        assertThrows(NoSuchMethodException.class, () -> VoucherSpendingCondition.class.getConstructor());
    }

    /**
     * The condition never consults the vault: the swap's hold is the double-spend check
     * (SwapProofHoldReportsSpentInputTest), and vouchers can only be spent through swap, since
     * melt refuses them. The per-input lookup this replaced cost one vault round trip per voucher
     * input on every swap, and was advisory anyway: a vault outage let the proof through.
     */
    @Test
    void verify_DoesNotConsultTheVault() throws CashuErrorException {
        Proof<VoucherSecret> proof = createVoucherProof(8, "00abc123def45678");

        PrivateKey mockKey = Mockito.mock(PrivateKey.class);
        Mockito.when(mockKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(mockKey);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);

            assertDoesNotThrow(() -> condition.verify(proof));
        }
        Mockito.verifyNoInteractions(mockProofVaultService);
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
        Mockito.when(mockProofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);

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
        Mockito.when(mockProofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);

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
        Mockito.when(mockProofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);

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
        Mockito.when(mockProofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);

        // Mock: keyset key lookup returns null (key not found)
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(null);

        // Act & Assert
        assertThrows(CashuErrorException.class, () -> condition.verify(proof));
    }
}
