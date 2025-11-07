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
import xyz.tcheeric.cashu.common.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.voucher.domain.VoucherSecret;
import xyz.tcheeric.cashu.common.Proof;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        // Create a real VoucherSecret
        VoucherSecret voucherSecret = VoucherSecret.create(
                "test-merchant",
                "sat",
                5000L,
                null,
                "Test voucher"
        );

        boolean result = VoucherSecretDetector.isVoucherSecret(voucherSecret);
        assertTrue(result,
                "VoucherSecretDetector should return true for actual VoucherSecret");
    }

    /**
     * E2E Test: Model B Rejection - Voucher secret in swap operation.
     *
     * <p>This is a comprehensive end-to-end test that verifies Model B enforcement:
     * vouchers cannot be used in mint swap operations and must be rejected.
     *
     * <p>Test flow:
     * <ol>
     *   <li>Create a valid VoucherSecret (representing a gift card voucher)</li>
     *   <li>Create a Proof with the VoucherSecret</li>
     *   <li>Attempt to swap the voucher proof at the mint</li>
     *   <li>Verify the swap is rejected with appropriate error message</li>
     * </ol>
     *
     * <p>This test satisfies task 6.3 from the implementation plan:
     * "Write E2E test: Model B rejection"
     */
    @Test
    public void e2eTest_ModelB_VoucherRejectedInSwap() {
        // ========== STEP 1: Create VoucherSecret ==========
        VoucherSecret voucherSecret = VoucherSecret.create(
                "coffee-shop-123",
                "sat",
                10000L,
                System.currentTimeMillis() / 1000 + 86400 * 30, // Expires in 30 days
                "Coffee shop gift card - $10"
        );

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

        // ========== STEP 4: Verify Rejection ==========
        CashuErrorException exception = assertThrows(
                CashuErrorException.class,
                task::execute,
                "Swap with voucher secret should be rejected (Model B)"
        );

        // Verify error message contains voucher rejection info
        String errorMessage = exception.getMessage();
        assertTrue(
                errorMessage.contains("voucher") || errorMessage.contains("Voucher"),
                "Error message should mention voucher: " + errorMessage
        );
        assertTrue(
                errorMessage.contains("Model B") || errorMessage.contains("merchant"),
                "Error message should explain Model B restriction: " + errorMessage
        );
    }

    /**
     * E2E Test: Model B Rejection - Multiple voucher proofs.
     *
     * <p>This test verifies that when a swap request contains multiple voucher proofs,
     * all are rejected with Model B error.
     */
    @Test
    public void e2eTest_ModelB_MultipleVoucherProofs_Rejected() {
        // Create two voucher proofs
        VoucherSecret voucher1 = VoucherSecret.create(
                "restaurant-xyz",
                "sat",
                5000L,
                null,
                "Restaurant voucher"
        );

        VoucherSecret voucher2 = VoucherSecret.create(
                "cafe-abc",
                "sat",
                3000L,
                null,
                "Cafe voucher"
        );

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

        // Should be rejected immediately on first voucher proof
        CashuErrorException exception = assertThrows(
                CashuErrorException.class,
                task::execute,
                "Swap with multiple voucher proofs should be rejected"
        );

        // Verify error mentions voucher rejection
        String errorMessage = exception.getMessage();
        assertTrue(
                errorMessage.contains("voucher") || errorMessage.contains("Voucher"),
                "Error should mention voucher rejection: " + errorMessage
        );
        assertTrue(
                errorMessage.contains("Model B") || errorMessage.contains("merchant"),
                "Error should explain Model B: " + errorMessage
        );
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
        VoucherSecret voucherSecret = VoucherSecret.create(
                "test-merchant",
                "sat",
                1000L,
                null,
                null
        );

        // Verify class name detection
        String className = voucherSecret.getClass().getName();
        assertEquals("xyz.tcheeric.cashu.voucher.domain.VoucherSecret", className,
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

