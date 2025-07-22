package xyz.tcheeric.test.protocol.tasks;

import lombok.extern.java.Log;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.P2PKSecret;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.admin.VaultUtil;
import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import xyz.tcheeric.cashu.mint.proto.tasks.VerifyProofsTask;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.api.DBVault;
import xyz.tcheeric.test.MintUtilTest;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Log
public class VerifyProofTest {

    private final VaultUtil vaultUtil = new VaultUtil(new MintUtilTest());

    @BeforeEach
    public void setUp() throws Exception {
        vaultUtil.createVault();
    }

    @AfterEach
    public void tearDown() {
    }

    @Test
    public void validate() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> postSwapRequest = new PostSwapRequest<>();

        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<RandomStringSecret> randomStringSecretVerifyProofsTask = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());

        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            assertNull(randomStringSecretVerifyProofsTask.execute());
        }
    }

    @Test
    public void validateInvalidAmount() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> postSwapRequest = new PostSwapRequest<>();

        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(160);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());

        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("validate_amounts_error", exception.getMessage());
        }
    }

    @Test
    public void validateInvalidProof() throws CashuErrorException {
        var mint = spy(new Mint());
        PostSwapRequest<RandomStringSecret> postSwapRequest = new PostSwapRequest<>();

        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b5a8"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());

        when(mint.getKeySets()).thenReturn(Set.of(new KeySet("004cf8cba2f93266", "sat", null, 0)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("verify_proof_failed_error", exception.getMessage());
        }
    }

    @Test
    public void validateProofNotFound() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> postSwapRequest = new PostSwapRequest<>();

        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("fake_ks_id");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<RandomStringSecret> randomStringSecretVerifyProofsTask = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());

        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, randomStringSecretVerifyProofsTask::execute);
            assertEquals("verify_proof_key_set_not_found:" + proof.getKeySetId(), exception.getMessage());
        }
    }

    @Test
    public void verifySuccess() throws Exception {
        PrivateKey recipient = PrivateKey.fromBytes(Schnorr.generatePrivateKey());

        log.log(Level.INFO, "Private key: {0}", recipient);
        P2PKSecret secret = new P2PKSecret(Schnorr.genPubKey(recipient.toBytes()));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        //secret.setLockTime(Integer.MAX_VALUE);
        secret.setNSigs(1);
        secret.addPubKey(Hex.toHexString(secret.getData()));

        Witness proofWitness = new Witness();
        proofWitness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(secret.getData()), recipient.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setAmount(16);
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(secret);
        proof.setKeySetId("004cf8cba2f93266");
        proof.setWitness(proofWitness);

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        PostSwapRequest<P2PKSecret> postSwapRequest = new PostSwapRequest<>();
        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());
        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            log.log(Level.INFO, "Data: {0} - Public key: {1} - Signature: {2}", new Object[]{Hex.toHexString(secret.getData()), secret.getPubKeys(), proofWitness.getSignatures()});
            assertNull(task.execute());
        }
    }

    @Test
    public void verify_invalid_refund_signature() throws Exception {
        PrivateKey recipient = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        byte[] data = Schnorr.genPubKey(recipient.toBytes());
        P2PKSecret secret = new P2PKSecret(data);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        secret.setLockTime(1);
        secret.setNSigs(1);
        secret.addPubKey(Hex.toHexString(data));
        secret.addRefund("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2");

        Witness proofWitness = new Witness();
        proofWitness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(recipient.toBytes()), recipient.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setAmount(16);
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(secret);
        proof.setKeySetId("004cf8cba2f93266");
        proof.setWitness(proofWitness);

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        PostSwapRequest<P2PKSecret> postSwapRequest = new PostSwapRequest<>();
        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());
        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("verify_invalid_refund_signature", exception.getMessage());
        }
    }

    @Test
    public void verify_invalid_number_of_signatures() throws Exception {
        PrivateKey recipient = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKSecret secret = new P2PKSecret(Schnorr.genPubKey(recipient.toBytes()));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        secret.setLockTime(1);
        secret.setNSigs(3);
        secret.addPubKey(Hex.toHexString(secret.getData()));

        Witness proofWitness = new Witness();
        proofWitness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(secret.getData()), recipient.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setAmount(16);
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(secret);
        proof.setKeySetId("004cf8cba2f93266");
        proof.setWitness(proofWitness);

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        PostSwapRequest<P2PKSecret> postSwapRequest = new PostSwapRequest<>();
        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());
        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("verify_invalid_number_of_signatures", exception.getMessage());
        }
    }

    @Test
    public void verify_locktime_not_reached() throws Exception {
        PrivateKey recipient = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKSecret secret = new P2PKSecret(Schnorr.genPubKey(recipient.toBytes()));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        secret.setLockTime(Integer.MAX_VALUE);
        secret.setNSigs(1);
        secret.addPubKey(Hex.toHexString(secret.getData()));

        Witness proofWitness = new Witness();
        proofWitness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(secret.getData()), recipient.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setAmount(16);
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(secret);
        proof.setKeySetId("004cf8cba2f93266");
        proof.setWitness(proofWitness);

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        PostSwapRequest<P2PKSecret> postSwapRequest = new PostSwapRequest<>();
        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve( false)).thenReturn(proof.getUnblindedSignature().toString());
        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("verify_locktime_not_reached", exception.getMessage());
        }
    }

    @Test
    public void complex_example_test() throws Exception {
        PrivateKey recipient = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        byte[] data = Schnorr.genPubKey(recipient.toBytes());
        P2PKSecret secret = new P2PKSecret(data);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_ALL);
        secret.setLockTime(1689418329);
        secret.setNSigs(2);
        secret.addPubKey(Hex.toHexString(data));
        secret.addPubKey("023192200a0cfd3867e48eb63b03ff599c7e46c8f4e41146b2d281173ca6c50c54");
        secret.addRefund(Hex.toHexString(data));

        Witness proofWitness = new Witness();
        proofWitness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(data), recipient.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setAmount(16);
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(secret);
        proof.setKeySetId("004cf8cba2f93266");
        proof.setWitness(proofWitness);

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        Witness blindedWitness = new Witness();
        blindedWitness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(data), recipient.toBytes())));
        blindedMessage.setWitness(blindedWitness);

        PostSwapRequest<P2PKSecret> postSwapRequest = new PostSwapRequest<>();
        postSwapRequest.setInputs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(DBVault.class);
        when(vault.retrieve(false)).thenReturn(proof.getUnblindedSignature().toString());
        when(mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            assertNull(task.execute());
        }
    }
}
