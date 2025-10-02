package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.gateway.common.Gateway;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Slf4j
public class MeltTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

    private MockedStatic<ProofEntity> proofEntityMock;

    @BeforeEach
    public void setUpProofEntityMock() {
        proofEntityMock = Mockito.mockStatic(ProofEntity.class);
        proofEntityMock.when(() -> ProofEntity.fromProof(Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> new ProofEntity());
    }

    @AfterEach
    public void tearDownProofEntityMock() {
        proofEntityMock.close();
    }

    /**
     * Ensures successful melts persist proofs as pending after payment succeeds.
     */
    @Test
    public void mockMelt() throws CashuErrorException {
        Proof<RandomStringSecret> proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        RSSProof proof1 = new RSSProof();
        proof1.setUnblindedSignature(Signature.fromString("02d908e2a5ce0a6ce6228667d4f33470e8308dce587a7f1d7b3114873d5d02fc77"));
        proof1.setSecret(RandomStringSecret.fromString("84ace011105717841eac2af8a96acb3167a77d3cec5fbb4b3a8ccaf64d78d7c8"));
        proof1.setAmount(16);
        proof1.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        BlindedMessage blindedMessage1 = new BlindedMessage();
        blindedMessage1.setAmount(16);
        blindedMessage1.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("031f5a5e834c6654753263cea178bef291eb27c39bf87fec4199d95d43132c665c"));

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest();
        request.setInputs(List.of(proof, proof1));
        request.setBlindedMessages(List.of(blindedMessage, blindedMessage1));

        PostMeltRequest<RandomStringSecret> postMeltRequest = new PostMeltRequest();
        postMeltRequest.setQuoteId("0x1234567890");
        postMeltRequest.setInputs(List.of(proof, proof1));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(32);
        when(mockGateway.getFeeReserve(anyString())).thenReturn(0);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(anyString())).thenReturn(new xyz.tcheeric.cashu.vault.db.model.MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(KeySet.builder().id("004cf8cba2f93266").unit("sat").build()));
        Mockito.when(mintLoadService.keySet(anyString())).thenReturn(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());

        MeltTask<RandomStringSecret> task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        PostMeltResponse postMeltResponse = task.execute();

        assertTrue(postMeltResponse.isPaid());

        ArgumentCaptor<ProofEntity> pendingCaptor = ArgumentCaptor.forClass(ProofEntity.class);
        Mockito.verify(proofVaultService, Mockito.times(2)).storePending(pendingCaptor.capture());
        pendingCaptor.getAllValues().forEach(entity -> assertEquals(ProofEntity.STATE_PENDING, entity.getState()));

        InOrder inOrder = Mockito.inOrder(mockGateway, proofVaultService);
        inOrder.verify(mockGateway).pay(postMeltRequest.getQuoteId());
        inOrder.verify(mockGateway).checkPaymentStatus(postMeltRequest.getQuoteId());
        inOrder.verify(proofVaultService, Mockito.times(2)).storePending(Mockito.any());
    }

    /**
     * Validates that melts with insufficient value fail before persistence or payment occurs.
     */
    @Test
    public void mockMeltWithFees() throws CashuErrorException, JsonProcessingException {
        Proof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest();
        request.setInputs(List.of(proof));
        request.setBlindedMessages(List.of(blindedMessage));

        PostMeltRequest<RandomStringSecret> postMeltRequest = new PostMeltRequest();
        postMeltRequest.setQuoteId("0x1234567890");
        postMeltRequest.setInputs(List.of(proof));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(256);
        when(mockGateway.getFeeReserve(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(anyString())).thenReturn(new xyz.tcheeric.cashu.vault.db.model.MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(KeySet.builder().id("004cf8cba2f93266").unit("sat").build()));
        Mockito.when(mintLoadService.keySet(anyString())).thenReturn(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());

        MeltTask task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        // Assert that a CashuErrorException is thrown
        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        ErrorResponse error = new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class);
        assertEquals("melt_proof_amount_error", error.code());
        assertEquals("Proof amount error", error.message());
        Mockito.verify(proofVaultService, Mockito.never()).storePending(Mockito.any());
        Mockito.verify(mockGateway, Mockito.never()).pay(anyString());
    }

    /**
     * Confirms that an unpaid invoice produces the expected Cashu error response.
     */
    @Test
    public void mockMeltNotPaid() throws CashuErrorException, JsonProcessingException {
        Proof<RandomStringSecret> proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        PostMeltRequest<RandomStringSecret> postMeltRequest = new PostMeltRequest();
        postMeltRequest.setQuoteId("0x1234567890");
        postMeltRequest.setInputs(List.of(proof));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(16);
        when(mockGateway.getFeeReserve(anyString())).thenReturn(0);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(false);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(anyString())).thenReturn(new xyz.tcheeric.cashu.vault.db.model.MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.keySet(anyString())).thenReturn(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());

        MeltTask<RandomStringSecret> task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        ErrorResponse error = new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class);
        assertEquals("melt_invoice_not_paid_error", error.code());
        assertEquals("Invoice not paid", error.message());
    }

    /**
     * Ensures the melt flow aborts before payment when persisting pending proofs fails.
     */
    @Test
    public void mockMeltPendingUpdateFails() throws CashuErrorException, JsonProcessingException {
        Proof<RandomStringSecret> proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        PostMeltRequest<RandomStringSecret> postMeltRequest = new PostMeltRequest();
        postMeltRequest.setQuoteId("0x1234567890");
        postMeltRequest.setInputs(List.of(proof));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(16);
        when(mockGateway.getFeeReserve(anyString())).thenReturn(0);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(anyString())).thenReturn(new xyz.tcheeric.cashu.vault.db.model.MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        Mockito.doThrow(new IllegalStateException("fail")).when(proofVaultService).storePending(Mockito.any());

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint(UUID.randomUUID().toString());
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.keySet(anyString())).thenReturn(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());

        MeltTask<RandomStringSecret> task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        ErrorResponse error = new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class);
        assertEquals("melt_proof_pending_error", error.code());
        Mockito.verify(proofVaultService).storePending(Mockito.any());
        Mockito.verify(mockGateway).pay(postMeltRequest.getQuoteId());
        Mockito.verify(mockGateway).checkPaymentStatus(postMeltRequest.getQuoteId());
    }

    /**
     * Ensures melts referencing the same proof serialize so the second request waits for the first to finish.
     */
    @Test
    public void concurrentMeltsShareProofsSerialize() throws Exception {
        Proof<RandomStringSecret> firstProof = new RSSProof();
        firstProof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        firstProof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        firstProof.setAmount(16);
        firstProof.setKeySetId(VALID_KEYSET_ID);

        Proof<RandomStringSecret> secondProof = new RSSProof();
        secondProof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        secondProof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        secondProof.setAmount(16);
        secondProof.setKeySetId(VALID_KEYSET_ID);

        PostMeltRequest<RandomStringSecret> firstRequest = new PostMeltRequest<>();
        firstRequest.setQuoteId("quote-1");
        firstRequest.setInputs(List.of(firstProof));

        PostMeltRequest<RandomStringSecret> secondRequest = new PostMeltRequest<>();
        secondRequest.setQuoteId("quote-2");
        secondRequest.setInputs(List.of(secondProof));

        Gateway firstGateway = Mockito.mock(Gateway.class);
        Gateway secondGateway = Mockito.mock(Gateway.class);
        Mockito.when(firstGateway.getAmount(Mockito.anyString())).thenReturn(16);
        Mockito.when(firstGateway.getRequest(Mockito.anyString())).thenReturn("request-1");
        Mockito.when(firstGateway.getFeeReserve(Mockito.anyString())).thenReturn(0);
        Mockito.when(firstGateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(firstGateway.getPaymentPreimage(Mockito.anyString())).thenReturn("preimage-1");

        CountDownLatch firstPayInvoked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            firstPayInvoked.countDown();
            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release first melt");
            }
            return null;
        }).when(firstGateway).pay(Mockito.anyString());

        Mockito.when(secondGateway.getAmount(Mockito.anyString())).thenReturn(16);
        Mockito.when(secondGateway.getRequest(Mockito.anyString())).thenReturn("request-2");
        Mockito.when(secondGateway.getFeeReserve(Mockito.anyString())).thenReturn(0);
        Mockito.when(secondGateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(secondGateway.getPaymentPreimage(Mockito.anyString())).thenReturn("preimage-2");

        CountDownLatch secondPayCalled = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            secondPayCalled.countDown();
            return null;
        }).when(secondGateway).pay(Mockito.anyString());

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(firstGateway, secondGateway);
        Mockito.when(service.getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(Mockito.anyString())).thenReturn(new xyz.tcheeric.cashu.vault.db.model.MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        KeySet keySet = KeySet.builder().id(VALID_KEYSET_ID).unit("sat").build();
        Mockito.when(mintLoadService.keySet(Mockito.anyString())).thenReturn(keySet);

        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(keySet);

        MeltTask<RandomStringSecret> firstTask = new MeltTask(firstRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService) {
            @Override
            public boolean verify(@NonNull Proof proof) {
                return true;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected InvalidateProofsTask<RandomStringSecret> createInvalidateProofsTask(List proofs) {
                List<Proof<RandomStringSecret>> typedProofs = (List<Proof<RandomStringSecret>>) proofs;
                return new InvalidateProofsTask<>(mint, typedProofs, mintVaultService, proofVaultService) {
                    @Override
                    public List<Proof<RandomStringSecret>> execute() {
                        return typedProofs;
                    }
                };
            }
        };

        MeltTask<RandomStringSecret> secondTask = new MeltTask(secondRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService) {
            @Override
            public boolean verify(@NonNull Proof proof) {
                return true;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected InvalidateProofsTask<RandomStringSecret> createInvalidateProofsTask(List proofs) {
                List<Proof<RandomStringSecret>> typedProofs = (List<Proof<RandomStringSecret>>) proofs;
                return new InvalidateProofsTask<>(mint, typedProofs, mintVaultService, proofVaultService) {
                    @Override
                    public List<Proof<RandomStringSecret>> execute() {
                        return typedProofs;
                    }
                };
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PostMeltResponse> firstFuture = executor.submit(firstTask::execute);
            assertTrue(firstPayInvoked.await(5, TimeUnit.SECONDS));

            Future<PostMeltResponse> secondFuture = executor.submit(secondTask::execute);
            assertFalse(secondPayCalled.await(200, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();

            assertTrue(secondPayCalled.await(5, TimeUnit.SECONDS));
            PostMeltResponse firstResponse = firstFuture.get(5, TimeUnit.SECONDS);
            PostMeltResponse secondResponse = secondFuture.get(5, TimeUnit.SECONDS);
            assertTrue(firstResponse.isPaid());
            assertTrue(secondResponse.isPaid());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Ensures melts with different proofs can progress independently without the global melt lock.
     */
    @Test
    public void concurrentMeltsWithDistinctProofsRunInParallel() throws Exception {
        Proof<RandomStringSecret> firstProof = new RSSProof();
        firstProof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        firstProof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        firstProof.setAmount(16);
        firstProof.setKeySetId(VALID_KEYSET_ID);

        Proof<RandomStringSecret> secondProof = new RSSProof();
        secondProof.setUnblindedSignature(Signature.fromString("02d908e2a5ce0a6ce6228667d4f33470e8308dce587a7f1d7b3114873d5d02fc77"));
        secondProof.setSecret(RandomStringSecret.fromString("84ace011105717841eac2af8a96acb3167a77d3cec5fbb4b3a8ccaf64d78d7c8"));
        secondProof.setAmount(16);
        secondProof.setKeySetId(VALID_KEYSET_ID);

        PostMeltRequest<RandomStringSecret> firstRequest = new PostMeltRequest<>();
        firstRequest.setQuoteId("quote-3");
        firstRequest.setInputs(List.of(firstProof));

        PostMeltRequest<RandomStringSecret> secondRequest = new PostMeltRequest<>();
        secondRequest.setQuoteId("quote-4");
        secondRequest.setInputs(List.of(secondProof));

        Gateway firstGateway = Mockito.mock(Gateway.class);
        Gateway secondGateway = Mockito.mock(Gateway.class);
        Mockito.when(firstGateway.getAmount(Mockito.anyString())).thenReturn(16);
        Mockito.when(firstGateway.getRequest(Mockito.anyString())).thenReturn("request-3");
        Mockito.when(firstGateway.getFeeReserve(Mockito.anyString())).thenReturn(0);
        Mockito.when(firstGateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(firstGateway.getPaymentPreimage(Mockito.anyString())).thenReturn("preimage-3");

        CountDownLatch firstPayInvoked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            firstPayInvoked.countDown();
            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release first melt");
            }
            return null;
        }).when(firstGateway).pay(Mockito.anyString());

        Mockito.when(secondGateway.getAmount(Mockito.anyString())).thenReturn(16);
        Mockito.when(secondGateway.getRequest(Mockito.anyString())).thenReturn("request-4");
        Mockito.when(secondGateway.getFeeReserve(Mockito.anyString())).thenReturn(0);
        Mockito.when(secondGateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(secondGateway.getPaymentPreimage(Mockito.anyString())).thenReturn("preimage-4");

        CountDownLatch secondPayCalled = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            secondPayCalled.countDown();
            return null;
        }).when(secondGateway).pay(Mockito.anyString());

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(firstGateway, secondGateway);
        Mockito.when(service.getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(Mockito.anyString())).thenReturn(new xyz.tcheeric.cashu.vault.db.model.MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        KeySet keySet = KeySet.builder().id(VALID_KEYSET_ID).unit("sat").build();
        Mockito.when(mintLoadService.keySet(Mockito.anyString())).thenReturn(keySet);

        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(keySet);

        MeltTask<RandomStringSecret> firstTask = new MeltTask(firstRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService) {
            @Override
            public boolean verify(@NonNull Proof proof) {
                return true;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected InvalidateProofsTask<RandomStringSecret> createInvalidateProofsTask(List proofs) {
                List<Proof<RandomStringSecret>> typedProofs = (List<Proof<RandomStringSecret>>) proofs;
                return new InvalidateProofsTask<>(mint, typedProofs, mintVaultService, proofVaultService) {
                    @Override
                    public List<Proof<RandomStringSecret>> execute() {
                        return typedProofs;
                    }
                };
            }
        };

        MeltTask<RandomStringSecret> secondTask = new MeltTask(secondRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService) {
            @Override
            public boolean verify(@NonNull Proof proof) {
                return true;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected InvalidateProofsTask<RandomStringSecret> createInvalidateProofsTask(List proofs) {
                List<Proof<RandomStringSecret>> typedProofs = (List<Proof<RandomStringSecret>>) proofs;
                return new InvalidateProofsTask<>(mint, typedProofs, mintVaultService, proofVaultService) {
                    @Override
                    public List<Proof<RandomStringSecret>> execute() {
                        return typedProofs;
                    }
                };
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PostMeltResponse> firstFuture = executor.submit(firstTask::execute);
            assertTrue(firstPayInvoked.await(5, TimeUnit.SECONDS));

            Future<PostMeltResponse> secondFuture = executor.submit(secondTask::execute);
            assertTrue(secondPayCalled.await(5, TimeUnit.SECONDS));
            PostMeltResponse secondResponse = secondFuture.get(5, TimeUnit.SECONDS);
            assertTrue(secondResponse.isPaid());

            releaseFirst.countDown();

            PostMeltResponse firstResponse = firstFuture.get(5, TimeUnit.SECONDS);
            assertTrue(firstResponse.isPaid());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Verifies that valid proofs pass the verification helper used by melts.
     */
    @Test
    public void verify() throws CashuErrorException {
        Proof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(anyString())).thenReturn(new xyz.tcheeric.cashu.vault.db.model.MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));

        MeltTask<RandomStringSecret> task = new MeltTask(new PostMeltRequest(), PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        boolean result = task.verify(proof);
        assertTrue(result);
    }

}
