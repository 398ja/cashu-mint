package protocol.tasks;

import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.PrivateKey;
import cashu.common.model.Proof;
import cashu.common.model.PublicKey;
import cashu.common.model.Secret;
import cashu.common.model.Signature;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.CashuErrorException;
import cashu.crypto.BDHKEUtils;
import cashu.gateway.Gateway;
import cashu.mint.proto.tasks.MeltTask;
import cashu.mint.admin.model.MintDto;
import cashu.mint.proto.util.MintUtil;
import cashu.util.Utils;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import cashu.mint.admin.VaultUtil;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class MeltTest {

    private VaultUtil vaultUtil;

    @Before
    public void setUp() throws IOException, CashuErrorException  {
        vaultUtil = new VaultUtil(getClass().getResourceAsStream("/mint.json"));
        vaultUtil.createVault();
    }

    @After
    public void tearDown() throws CashuErrorException {
        vaultUtil.deleteVault();
    }

    @Test
    public void melt() throws CashuErrorException {
        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        Proof proof1 = new Proof();
        proof1.setUnblindedSignature(Signature.fromString("02d908e2a5ce0a6ce6228667d4f33470e8308dce587a7f1d7b3114873d5d02fc77"));
        proof1.setSecret(Secret.fromString("84ace011105717841eac2af8a96acb3167a77d3cec5fbb4b3a8ccaf64d78d7c8"));
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

        PostSwapRequest request = new PostSwapRequest();
        request.setProofs(List.of(proof, proof1));
        request.setBlindedMessages(List.of(blindedMessage, blindedMessage1));

        PostMeltRequest postMeltRequest = new PostMeltRequest();
        postMeltRequest.setQuoteId("0x1234567890");
        postMeltRequest.setProofs(List.of(proof, proof1));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(32);
        when(mockGateway.getFeeReserve(anyString())).thenReturn(0);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);


        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MeltTask task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint);

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.createGateway(PaymentMethod.MOCK, "melt"))
                    .thenReturn(mockGateway);
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            PostMeltResponse postMeltResponse = task.execute();

            archiveProof();

            assertTrue(postMeltResponse.isPaid());
        }
    }

    @Test
    public void meltWithFees() throws CashuErrorException {
        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        PostSwapRequest request = new PostSwapRequest();
        request.setProofs(List.of(proof));
        request.setBlindedMessages(List.of(blindedMessage));

        PostMeltRequest postMeltRequest = new PostMeltRequest();
        postMeltRequest.setQuoteId("0x1234567890");
        postMeltRequest.setProofs(List.of(proof));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(256);
        when(mockGateway.getFeeReserve(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MeltTask task = new MeltTask(postMeltRequest, PaymentMethod.MOCK, mint);

        archiveProof();

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            mintUtil.when(() -> MintUtil.createGateway(PaymentMethod.MOCK, "melt"))
                    .thenReturn(mockGateway);

            // Assert that a CashuErrorException is thrown
            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("melt_proof_amount_error", exception.getMessage());
        }
    }

    @Test
    public void verify() {
        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MeltTask task = new MeltTask(new PostMeltRequest(), PaymentMethod.MOCK, mint);

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
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
