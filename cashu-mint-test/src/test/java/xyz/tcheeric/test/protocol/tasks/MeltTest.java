package xyz.tcheeric.test.protocol.tasks;

import lombok.extern.java.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteBolt11Request;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.admin.VaultUtil;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.tasks.MeltTask;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;
import xyz.tcheeric.common.util.Configuration;
import xyz.tcheeric.test.MintUtilTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Log
public class MeltTest {

    private VaultUtil vaultUtil;

    private static final Configuration config = new Configuration("phoenixd");

    @BeforeEach
    public void setUp() throws Exception {
        vaultUtil = new VaultUtil(new MintUtilTest(UUID.randomUUID().toString(), "sat"));
        vaultUtil.createVault();
    }

    @AfterEach
    public void tearDown() throws CashuErrorException {
        vaultUtil.deleteVault();
    }

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
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        BlindedMessage blindedMessage1 = new BlindedMessage();
        blindedMessage1.setAmount(16);
        blindedMessage1.setKeySetId("004cf8cba2f93266");
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


        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MeltTask<RandomStringSecret> task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint);

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.createGateway(PaymentMethod.MOCK))
                    .thenReturn(mockGateway);
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            PostMeltResponse postMeltResponse = task.execute();

            archiveProof();

            assertTrue(postMeltResponse.isPaid());
        }
    }

    @Test
    public void melt() throws CashuErrorException {
        Proof<RandomStringSecret> proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        Proof<RandomStringSecret> proof1 = new RSSProof();
        proof1.setUnblindedSignature(Signature.fromString("02d908e2a5ce0a6ce6228667d4f33470e8308dce587a7f1d7b3114873d5d02fc77"));
        proof1.setSecret(RandomStringSecret.fromString("84ace011105717841eac2af8a96acb3167a77d3cec5fbb4b3a8ccaf64d78d7c8"));
        proof1.setAmount(16);
        proof1.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        BlindedMessage blindedMessage1 = new BlindedMessage();
        blindedMessage1.setAmount(16);
        blindedMessage1.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("031f5a5e834c6654753263cea178bef291eb27c39bf87fec4199d95d43132c665c"));

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest();
        request.setInputs(List.of(proof, proof1));
        request.setBlindedMessages(List.of(blindedMessage, blindedMessage1));

        String requestString = MintProtocolUtil.createLightningAddressRequest(config.get("payee"), 32, "Melt request_" + UUID.randomUUID());

        PostMeltQuoteBolt11Request postMeltQuoteBolt11Request = new PostMeltQuoteBolt11Request();
        postMeltQuoteBolt11Request.setRequest(requestString);
        postMeltQuoteBolt11Request.setUnit("sat");

        PostMeltQuoteResponse postMeltQuoteResponse = NUT05.quote(postMeltQuoteBolt11Request, PaymentMethod.BOLT11);

        PostMeltRequest<RandomStringSecret> postMeltRequest = new PostMeltRequest();
        postMeltRequest.setQuoteId(postMeltQuoteResponse.getQuoteId());
        postMeltRequest.setInputs(List.of(proof, proof1));

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        //MeltTask meltTask = new MeltTask(postMeltRequest, PaymentMethod.BOLT11, mint);

        PostMeltResponse postMeltResponse = NUT05.melt(postMeltRequest, PaymentMethod.BOLT11); //meltTask.execute();

        archiveProof();

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
        blindedMessage.setKeySetId("004cf8cba2f93266");
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

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MeltTask task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint);

        archiveProof();

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            mintUtil.when(() -> MintProtocolUtil.createGateway(PaymentMethod.MOCK))
                    .thenReturn(mockGateway);

            // Assert that a CashuErrorException is thrown
            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("melt_proof_amount_error", exception.getMessage());
        }
    }

    @Test
    public void verify() {
        Proof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MeltTask<RandomStringSecret> task = new MeltTask(new PostMeltRequest(), PaymentMethod.MOCK, mint);

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            boolean result = task.verify(proof);
            assertTrue(result);
        }
    }

    private void archiveProof() throws CashuErrorException {
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");
        Mint mint = MintDto.toMint(vaultUtil.getMint());
        ProofConfiguration configuration = new ProofConfiguration(new MintConfiguration(mint.getId()), "0392810a73efd77346d3658bf0dc7004fae1e201a03bd511d8077956d7785a8355", Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault vault = new FSProofVault(configuration);
        vault.archive("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");
    }
}
