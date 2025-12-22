package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.gateway.common.Gateway;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

    public class MeltTokensTaskTest {

    /**
     * Ensures the melt task wires together the protocol, gateway, and vault services when melting tokens.
     */
    @Test
    public void execute() throws CashuErrorException {
        Proof<RandomStringSecret> proof = new RSSProof();
        PrivateKey testPrivateKey = PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5");
        RandomStringSecret secret = RandomStringSecret.create();
        proof.setSecret(secret);
        proof.setAmount(16);
        proof.setKeySetId("ks1");
        // Build a valid C = hash_to_curve(secret) * k so BDHKE verification succeeds without static mocks
        byte[] yBytes = xyz.tcheeric.cashu.crypto.BDHKEUtils.hashToCurve(secret.toString());
        ECPoint Y = new SecP256K1Curve().decodePoint(yBytes);
        ECPoint C = Y.multiply(new BigInteger(1, testPrivateKey.getBytes()));
        proof.setUnblindedSignature(Signature.fromBytes(C.getEncoded(true)));

        PostMeltRequest<RandomStringSecret> request = new PostMeltRequest();
        request.setQuoteId("qid");
        request.setInputs(List.of(proof));

        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getAmount("qid")).thenReturn(16);
        when(gateway.getFeeReserve("qid")).thenReturn(0);
        when(gateway.checkPaymentStatus("qid")).thenReturn(true);

        MintProtocolService protocolService = Mockito.mock(MintProtocolService.class);
        when(protocolService.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        when(protocolService.getPrivateKey(anyString(), anyInt(), any())).thenReturn(testPrivateKey);

        MintLoadService loadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint(UUID.randomUUID().toString());
        when(loadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        when(loadService.keySet(anyString())).thenReturn(KeySet.builder().id("ks1").unit("sat").build());

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        when(mintVaultService.retrieveMint(mint.getId())).thenReturn(new MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        MeltTokensTask<RandomStringSecret> task = new MeltTokensTask<>(UUID.randomUUID(), request, PaymentMethod.MOCK,
                protocolService, loadService, mintVaultService, proofVaultService);

        PostMeltResponse resp = task.execute();
        assertTrue(resp.isPaid());
    }
}
