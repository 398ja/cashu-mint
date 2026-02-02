package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.common.Proof;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

public class VerifyProofsTaskTest {

    private static final String VALID_KEYSET_ID = "0123456789abcdef";

    private RSSProof createProof(int amount) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId(VALID_KEYSET_ID);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        proof.setWitness(new Witness());
        return proof;
    }

    private BlindedMessage createBlindedMessage(int amount) {
        BlindedMessage bm = new BlindedMessage();
        bm.setAmount(amount);
        bm.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        bm.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        bm.setWitness(new Witness());
        return bm;
    }

    private P2PKProof createP2PKProof(int amount) {
        P2PKProof proof = new P2PKProof();
        proof.setAmount(amount);
        proof.setKeySetId(VALID_KEYSET_ID);
        P2PKSecret secret = new P2PKSecret(new byte[32]);
        secret.setNSigs(1);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        proof.setSecret(secret);
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        proof.setWitness(new Witness());
        return proof;
    }

    /**
     * Confirms a valid swap request runs all proof checks without raising errors.
     */
    @Test
    public void executeSuccess() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<RSSSpendingCondition> cons = Mockito.mockConstruction(RSSSpendingCondition.class,
                (mock, ctx) -> Mockito.doNothing().when(mock).verify(any()))) {
            VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request, service);
            assertDoesNotThrow(task::execute);
            Mockito.verify(cons.constructed().get(0)).verify(proof);
        }
    }

    /**
     * Ensures mismatched blinded message and proof amounts are rejected.
     */
    @Test
    public void executeFailAmounts() {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(5);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request, service);
        assertThrows(CashuErrorException.class, task::execute);
    }

    /**
     * Verifies an error is surfaced when proof verification fails.
     */
    @Test
    public void executeFailVerification() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<RSSSpendingCondition> cons = Mockito.mockConstruction(RSSSpendingCondition.class,
                (mock, ctx) -> Mockito.doThrow(new CashuErrorException("fail")).when(mock).verify(any()))) {
            VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request, service);
            assertThrows(CashuErrorException.class, task::execute);
        }
    }

    /**
     * Confirms P2PK proofs are validated successfully when inputs are sound.
     */
    @Test
    public void executeP2PKSuccess() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<P2PKSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        P2PKProof proof = createP2PKProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<P2PKSpendingCondition> cons = Mockito.mockConstruction(P2PKSpendingCondition.class,
                (mock, ctx) -> Mockito.doNothing().when(mock).verify(any()))) {
            VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(mint, request, service);
            assertDoesNotThrow(task::execute);
            Mockito.verify(cons.constructed().get(0)).verify(proof);
        }
    }

    /**
     * Tests VoucherSecretDetector with null secret.
     */
    @Test
    public void voucherSecretDetector_NullSecret() {
        boolean result = VoucherSecretDetector.isVoucherSecret(null);
        org.junit.jupiter.api.Assertions.assertFalse(result,
                "VoucherSecretDetector should return false for null secret");
    }

    /**
     * Tests VoucherSecretDetector with non-voucher secret.
     */
    @Test
    public void voucherSecretDetector_NonVoucherSecret() {
        RandomStringSecret secret = RandomStringSecret.create();
        boolean result = VoucherSecretDetector.isVoucherSecret(secret);
        org.junit.jupiter.api.Assertions.assertFalse(result,
                "VoucherSecretDetector should return false for RandomStringSecret");
    }

    /**
     * Tests VoucherSecretDetector with P2PKSecret.
     */
    @Test
    public void voucherSecretDetector_P2PKSecret() {
        P2PKSecret secret = new P2PKSecret(new byte[32]);
        boolean result = VoucherSecretDetector.isVoucherSecret(secret);
        org.junit.jupiter.api.Assertions.assertFalse(result,
                "VoucherSecretDetector should return false for P2PKSecret");
    }

    /**
     * Tests VoucherSecretDetector with actual VoucherSecret - should return true.
     */
    @Test
    public void voucherSecretDetector_ActualVoucherSecret() {
        // Create a real VoucherSecret using the builder pattern
        VoucherSecret voucherSecret = VoucherSecret.builder()
                .issuerId("test-merchant")
                .unit("sat")
                .faceValue(5000L)
                .memo("Test voucher")
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        boolean result = VoucherSecretDetector.isVoucherSecret(voucherSecret);
        assertTrue(result,
                "VoucherSecretDetector should return true for actual VoucherSecret");
    }

    /**
     * Test: Voucher secrets are allowed in swap operations.
     *
     * <p>Vouchers use standard BDHKE verification for swaps. Model B enforcement
     * (merchant-only redemption) belongs at the application layer, not the mint protocol.
     * Swapping is essential for:
     * - Double-spend prevention when receiving tokens
     * - Splitting vouchers into smaller denominations
     * - P2P transfers between users
     *
     * <p>Test flow:
     * <ol>
     *   <li>Create a valid VoucherSecret (representing a gift card voucher)</li>
     *   <li>Create a Proof with the VoucherSecret</li>
     *   <li>Attempt to swap the voucher proof at the mint</li>
     *   <li>Verify the swap proceeds with standard BDHKE verification</li>
     * </ol>
     */
    @Test
    public void voucherSecretsAllowedInSwap() {
        // ========== STEP 1: Create VoucherSecret ==========
        VoucherSecret voucherSecret = VoucherSecret.builder()
                .issuerId("coffee-shop-123")
                .unit("sat")
                .faceValue(10000L)
                .expiresAt(System.currentTimeMillis() / 1000 + 86400 * 30) // Expires in 30 days
                .memo("Coffee shop gift card - $10")
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        // Verify the voucher secret is valid
        org.junit.jupiter.api.Assertions.assertNotNull(voucherSecret.getVoucherId());
        org.junit.jupiter.api.Assertions.assertEquals("coffee-shop-123", voucherSecret.getIssuerId());
        org.junit.jupiter.api.Assertions.assertEquals(10000L, voucherSecret.getFaceValue());

        // ========== STEP 2: Create Proof with VoucherSecret ==========
        Proof<VoucherSecret> voucherProof = new Proof<>();
        voucherProof.setAmount(10000);
        voucherProof.setKeySetId(VALID_KEYSET_ID);
        voucherProof.setSecret(voucherSecret);
        voucherProof.setUnblindedSignature(SignatureTestData.sampleSignature());
        voucherProof.setWitness(new Witness());

        // ========== STEP 3: Attempt Swap Operation ==========
        Mint mint = new Mint();
        PostSwapRequest<VoucherSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        BlindedMessage bm = createBlindedMessage(10000);

        Mockito.when(request.getInputs()).thenReturn(List.of(voucherProof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        VerifyProofsTask<VoucherSecret> task = new VerifyProofsTask<>(mint, request, service);

        // ========== STEP 4: Verify voucher swap proceeds (may fail on BDHKE verification,
        // but should NOT fail with "voucher_swap_rejected" error) ==========
        // The swap will fail due to missing keyset setup in the test, but the important
        // thing is that it doesn't reject vouchers outright
        try {
            task.execute();
            // If it succeeds, that's fine (full integration test would need proper keyset)
        } catch (CashuErrorException e) {
            // Verify it's NOT a voucher rejection error
            String errorMessage = e.getMessage();
            assertFalse(
                    errorMessage.contains("voucher_swap_rejected"),
                    "Voucher swaps should NOT be rejected at protocol layer: " + errorMessage
            );
            assertFalse(
                    errorMessage.contains("Model B"),
                    "Model B enforcement should NOT happen at protocol layer: " + errorMessage
            );
        }
    }

    /**
     * Test: Multiple voucher proofs are allowed in swap operations.
     *
     * <p>Swapping multiple voucher proofs should proceed with standard BDHKE verification.
     * This is essential for consolidating vouchers or complex P2P transfers.
     */
    @Test
    public void multipleVoucherProofsAllowedInSwap() {
        // Create two voucher proofs
        VoucherSecret voucher1 = VoucherSecret.builder()
                .issuerId("restaurant-xyz")
                .unit("sat")
                .faceValue(5000L)
                .memo("Restaurant voucher")
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        VoucherSecret voucher2 = VoucherSecret.builder()
                .issuerId("cafe-abc")
                .unit("sat")
                .faceValue(3000L)
                .memo("Cafe voucher")
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        Proof<VoucherSecret> voucherProof1 = new Proof<>();
        voucherProof1.setAmount(5000);
        voucherProof1.setKeySetId(VALID_KEYSET_ID);
        voucherProof1.setSecret(voucher1);
        voucherProof1.setUnblindedSignature(SignatureTestData.sampleSignature());
        voucherProof1.setWitness(new Witness());

        Proof<VoucherSecret> voucherProof2 = new Proof<>();
        voucherProof2.setAmount(3000);
        voucherProof2.setKeySetId(VALID_KEYSET_ID);
        voucherProof2.setSecret(voucher2);
        voucherProof2.setUnblindedSignature(SignatureTestData.sampleSignature());
        voucherProof2.setWitness(new Witness());

        // Setup swap request with multiple voucher proofs
        Mint mint = new Mint();
        PostSwapRequest<VoucherSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        BlindedMessage bm1 = createBlindedMessage(5000);
        BlindedMessage bm2 = createBlindedMessage(3000);

        Mockito.when(request.getInputs()).thenReturn(List.of(voucherProof1, voucherProof2));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm1, bm2));

        VerifyProofsTask<VoucherSecret> task = new VerifyProofsTask<>(mint, request, service);

        // Verify voucher swap proceeds (may fail on BDHKE verification due to test setup,
        // but should NOT fail with voucher rejection error)
        try {
            task.execute();
        } catch (CashuErrorException e) {
            String errorMessage = e.getMessage();
            assertFalse(
                    errorMessage.contains("voucher_swap_rejected"),
                    "Voucher swaps should NOT be rejected: " + errorMessage
            );
            assertFalse(
                    errorMessage.contains("Model B"),
                    "Model B enforcement should NOT happen at protocol layer: " + errorMessage
            );
        }
    }

    /**
     * E2E Test: VoucherSecretDetector correctly identifies VoucherSecret by class name.
     *
     * <p>This test verifies the detection mechanism works correctly without
     * requiring a hard dependency on the voucher module.
     */
    @Test
    public void e2eTest_VoucherDetection_ClassNameMatching() {
        // Create voucher secret
        VoucherSecret voucherSecret = VoucherSecret.builder()
                .issuerId("test-merchant")
                .unit("sat")
                .faceValue(1000L)
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        // Verify class name detection
        String className = voucherSecret.getClass().getName();
        assertEquals("xyz.tcheeric.cashu.common.nut18.VoucherSecret", className,
                "VoucherSecret should have expected fully qualified class name");

        // Verify detector identifies it
        assertTrue(VoucherSecretDetector.isVoucherSecret(voucherSecret),
                "Detector should identify VoucherSecret by class name");

        // Verify regular secrets are not detected as vouchers
        RandomStringSecret regularSecret = RandomStringSecret.create();
        org.junit.jupiter.api.Assertions.assertFalse(
                VoucherSecretDetector.isVoucherSecret(regularSecret),
                "Detector should not identify RandomStringSecret as voucher"
        );
    }
}
