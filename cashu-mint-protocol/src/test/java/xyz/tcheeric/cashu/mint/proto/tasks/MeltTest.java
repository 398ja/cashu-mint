package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Slf4j
public class MeltTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

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
        Mint mint = new Mint();
        mint.addKeySet(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(KeySet.builder().id("004cf8cba2f93266").unit("sat").build()));
        Mockito.when(mintLoadService.keySet(anyString())).thenReturn(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());

        MeltTask<RandomStringSecret> task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        PostMeltResponse postMeltResponse = task.execute();

        assertTrue(postMeltResponse.isPaid());

    }

    @Test
    public void mockMeltWithFees() throws CashuErrorException {
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
        Mint mint = new Mint();
        mint.addKeySet(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(KeySet.builder().id("004cf8cba2f93266").unit("sat").build()));
        Mockito.when(mintLoadService.keySet(anyString())).thenReturn(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());

        MeltTask task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        // Assert that a CashuErrorException is thrown
        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        assertEquals("melt_proof_amount_error", exception.getMessage());
    }

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
        Mint mint = new Mint();
        mint.addKeySet(KeySet.builder().id("004cf8cba2f93266").unit("sat").build());
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));

        MeltTask<RandomStringSecret> task = new MeltTask(new PostMeltRequest(), PaymentMethod.MOCK, mint, service, mintLoadService, mintVaultService, proofVaultService);

        boolean result = task.verify(proof);
        assertTrue(result);
    }

}
