package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static xyz.tcheeric.cashu.mint.proto.error.ErrorPayloads.keyOf;

public class SwapTaskTest {

    private static final String VALID_KEYSET_ID = "0123456789abcdef";

    private RSSProof createProof() {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setKeySetId(VALID_KEYSET_ID);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    // Distinct public keys, so a fixture pair is not itself a duplicate output.
    private static final List<String> BLINDED_MESSAGES = List.of(
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7",
            "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112");

    private int nextBlindedMessage;

    private BlindedMessage createBlindedMessage() {
        BlindedMessage bm = new BlindedMessage();
        bm.setAmount(1);
        bm.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        bm.setBlindedMessage(PublicKey.fromString(
                BLINDED_MESSAGES.get(nextBlindedMessage++ % BLINDED_MESSAGES.size())));
        return bm;
    }

    /**
     * Validates that a successful swap orchestrates proof verification, signing, and fee checks in order.
     */
    @Test
    public void execute() throws CashuErrorException {
        RSSProof proof = createProof();
        BlindedMessage bm = createBlindedMessage();

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof));
        request.setBlindedMessages(List.of(bm));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<InvalidateProofsTask> invalidateCons = Mockito.mockConstruction(InvalidateProofsTask.class,
                     (mock, ctx) -> Mockito.when(mock.execute()).thenReturn(List.of(proof)));
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(SignBlindedMessageTask.class,
                     (mock, ctx) -> Mockito.doReturn(new BlindSignature(
                             1,
                             KeysetId.fromString(VALID_KEYSET_ID),
                             SignatureTestData.sampleSignature(),
                             null))
                             .when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(VerifyFeesTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute())) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<RandomStringSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());
            PostSwapResponse response = task.execute();

            assertEquals(1, response.getBlindSignatures().size());
            Mockito.verify(verifyCons.constructed().get(0)).execute();
            Mockito.verify(invalidateCons.constructed().get(0)).execute();
            Mockito.verify(signCons.constructed().get(0)).execute();
            Mockito.verify(feesCons.constructed().get(0)).execute();
        }
    }

    /**
     * Ensures an informative error is returned when the mint cannot be loaded.
     */
    @Test
    public void executeMintNotFound() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(null);

        SwapTask<RandomStringSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        try {
            CashuErrorCode errorCode = exception.getErrorCode();
            assertEquals("swap_mint_not_found", errorCode.name());
            assertEquals("Mint not found", errorCode.getDefaultDetail());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Helper method to create a voucher proof
    private Proof<VoucherSecret> createVoucherProof() {
        VoucherSecret secret = VoucherSecret.builder()
                .issuerId("test-merchant")
                .unit("sat")
                .faceValue(100L)
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(100);
        proof.setKeySetId(VALID_KEYSET_ID);
        proof.setSecret(secret);
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    /**
     * P2-03: Verifies that mixing voucher and regular proofs is rejected.
     * SwapTask must reject swap requests that contain both voucher and regular proofs.
     */
    @Test
    public void execute_MixedProofTypes_Rejected() throws CashuErrorException {
        // Create mixed proof list with both regular and voucher proofs
        RSSProof regularProof = createProof();
        Proof<VoucherSecret> voucherProof = createVoucherProof();

        // Create a raw list that can hold both types
        List<Proof<? extends Secret>> mixedProofs = new ArrayList<>();
        mixedProofs.add(regularProof);
        mixedProofs.add(voucherProof);

        BlindedMessage bm1 = createBlindedMessage();
        BlindedMessage bm2 = createBlindedMessage();
        bm2.setAmount(100);

        @SuppressWarnings("unchecked")
        PostSwapRequest<Secret> request = new PostSwapRequest<>();
        request.setInputs((List) mixedProofs);
        request.setBlindedMessages(List.of(bm1, bm2));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class)) {
            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<Secret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);

            try {
                CashuErrorCode errorCode = exception.getErrorCode();
                assertEquals("mixed_proof_types_error", errorCode.name());
                assertTrue(errorCode.getDefaultDetail().contains("Cannot mix voucher and regular proofs"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * P2-04: Verifies that voucher-only proofs are accepted.
     * SwapTask should allow swaps with only voucher proofs.
     */
    @Test
    public void execute_VoucherOnlyProofs_Accepted() throws CashuErrorException {
        Proof<VoucherSecret> voucherProof1 = createVoucherProof();
        Proof<VoucherSecret> voucherProof2 = createVoucherProof();
        voucherProof2.setAmount(50);

        BlindedMessage bm = createBlindedMessage();
        bm.setAmount(150); // Total of both voucher proofs

        @SuppressWarnings("unchecked")
        PostSwapRequest<VoucherSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(voucherProof1, voucherProof2));
        request.setBlindedMessages(List.of(bm));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<InvalidateProofsTask> invalidateCons = Mockito.mockConstruction(InvalidateProofsTask.class,
                     (mock, ctx) -> Mockito.when(mock.execute()).thenReturn(List.of()));
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(SignBlindedMessageTask.class,
                     (mock, ctx) -> Mockito.doReturn(new BlindSignature(
                             150,
                             KeysetId.fromString(VALID_KEYSET_ID),
                             SignatureTestData.sampleSignature(),
                             null))
                             .when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(VerifyFeesTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute())) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<VoucherSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

            // Should not throw - voucher-only swaps are allowed
            PostSwapResponse response = assertDoesNotThrow(task::execute);
            assertEquals(1, response.getBlindSignatures().size());
        }
    }

    /**
     * P2-05: Verifies that regular-only proofs are accepted.
     * SwapTask should allow swaps with only regular proofs.
     */
    @Test
    public void execute_RegularOnlyProofs_Accepted() throws CashuErrorException {
        RSSProof regularProof1 = createProof();
        RSSProof regularProof2 = createProof();

        BlindedMessage bm1 = createBlindedMessage();
        BlindedMessage bm2 = createBlindedMessage();

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(regularProof1, regularProof2));
        request.setBlindedMessages(List.of(bm1, bm2));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<InvalidateProofsTask> invalidateCons = Mockito.mockConstruction(InvalidateProofsTask.class,
                     (mock, ctx) -> Mockito.when(mock.execute()).thenReturn(List.of(regularProof1, regularProof2)));
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(SignBlindedMessageTask.class,
                     (mock, ctx) -> Mockito.doReturn(new BlindSignature(
                             1,
                             KeysetId.fromString(VALID_KEYSET_ID),
                             SignatureTestData.sampleSignature(),
                             null))
                             .when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(VerifyFeesTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute())) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<RandomStringSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

            // Should not throw - regular-only swaps are allowed
            PostSwapResponse response = assertDoesNotThrow(task::execute);
            assertEquals(2, response.getBlindSignatures().size());
        }
    }

    /**
     * P4-07: Verifies that voucher swaps allow non-power-of-2 split amounts.
     * A 100-unit voucher can be split into 33 + 67 (free splitting).
     */
    @Test
    public void execute_VoucherSwap_NonPowerOf2Split_Accepted() throws CashuErrorException {
        // Create voucher proof with 100 units
        Proof<VoucherSecret> voucherProof = createVoucherProof();

        // Create outputs with non-power-of-2 amounts: 33 + 67 = 100
        BlindedMessage bm1 = createBlindedMessage();
        bm1.setAmount(33);  // Not a power of 2
        BlindedMessage bm2 = createBlindedMessage();
        bm2.setAmount(67);  // Not a power of 2

        @SuppressWarnings("unchecked")
        PostSwapRequest<VoucherSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(voucherProof));
        request.setBlindedMessages(List.of(bm1, bm2));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<InvalidateProofsTask> invalidateCons = Mockito.mockConstruction(InvalidateProofsTask.class,
                     (mock, ctx) -> Mockito.when(mock.execute()).thenReturn(List.of()));
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(SignBlindedMessageTask.class,
                     (mock, ctx) -> {
                         BlindedMessage bm = (BlindedMessage) ctx.arguments().get(1);
                         Mockito.doReturn(new BlindSignature(
                                 bm.getAmount(),
                                 KeysetId.fromString(VALID_KEYSET_ID),
                                 SignatureTestData.sampleSignature(),
                                 null)).when(mock).execute();
                     })) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<VoucherSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

            // Should succeed - voucher swaps allow arbitrary split amounts
            PostSwapResponse response = assertDoesNotThrow(task::execute);
            assertEquals(2, response.getBlindSignatures().size());
            assertEquals(33, response.getBlindSignatures().get(0).getAmount());
            assertEquals(67, response.getBlindSignatures().get(1).getAmount());
        }
    }

    /**
     * Verifies that voucher swaps reject mismatched total amounts.
     * Even with free splitting, input total must equal output total.
     */
    @Test
    public void execute_VoucherSwap_AmountMismatch_Rejected() throws CashuErrorException {
        // Create voucher proof with 100 units
        Proof<VoucherSecret> voucherProof = createVoucherProof();

        // Create outputs totaling 90 (not 100) - MISMATCH
        BlindedMessage bm1 = createBlindedMessage();
        bm1.setAmount(50);
        BlindedMessage bm2 = createBlindedMessage();
        bm2.setAmount(40);

        @SuppressWarnings("unchecked")
        PostSwapRequest<VoucherSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(voucherProof));
        request.setBlindedMessages(List.of(bm1, bm2));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute())) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<VoucherSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);

            try {
                CashuErrorCode errorCode = exception.getErrorCode();
                assertEquals("voucher_split_amount_mismatch", errorCode.name());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Dalia Phase 9: a zero-value IOU proof cannot be swapped — the mint refuses it before verification.
     */
    @Test
    public void execute_IouInputProof_Refused() throws Exception {
        String iouKeysetId = "0011aa22bb33cc44";
        RSSProof proof = new RSSProof();
        proof.setAmount(0);
        proof.setKeySetId(iouKeysetId);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof));
        request.setBlindedMessages(List.of());

        Mint mint = new Mint();
        Keys iouKeys = new Keys();
        iouKeys.put(BigInteger.ZERO, PublicKey.fromString(
                "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        mint.addKeySet(KeySet.builder().id(iouKeysetId).unit("iou").keys(iouKeys).build());

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class)) {
            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(Mockito.mock(MintProtocolService.class));

            SwapTask<RandomStringSecret> task = new SwapTask<>(
                    UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());
            CashuErrorException ex = assertThrows(CashuErrorException.class, task::execute);
            try {
                CashuErrorCode errorCode = ex.getErrorCode();
                assertEquals("iou_not_swappable", errorCode.name());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
