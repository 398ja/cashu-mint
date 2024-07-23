package protocol.tasks;

import cashu.common.model.BlindedMessage;
import cashu.common.model.KeySet;
import cashu.common.model.Mint;
import cashu.common.model.PrivateKey;
import cashu.common.model.Proof;
import cashu.common.model.PublicKey;
import cashu.common.model.Secret;
import cashu.common.model.Signature;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.util.CashuErrorException;
import cashu.mint.proto.tasks.VerifyProofsTask;
import cashu.mint.admin.model.KeySetDto;
import cashu.mint.admin.model.MintDto;
import cashu.mint.proto.util.MintUtil;
import cashu.vault.FSVault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import cashu.mint.admin.VaultUtil;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class VerifyProofTest {

    private VaultUtil vaultUtil;

    @Before
    public void setUp() throws IOException, CashuErrorException {
        vaultUtil = new VaultUtil(getClass().getResourceAsStream("/mint.json"));
        vaultUtil.createVault();
    }

    @After
    public void tearDown() throws CashuErrorException {
        vaultUtil.deleteVault();
    }

    @Test
    public void validate() throws CashuErrorException {
        PostSwapRequest postSwapRequest = new PostSwapRequest();

        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setProofs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask task = new VerifyProofsTask(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(FSVault.class);
        when(vault.retrieve(proof.getSecret().toString(), false)).thenReturn(proof.getUnblindedSignature().toString());

        when (mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            assertNull(task.execute());
        }
    }

    @Test
    public void validateInvalidAmount() throws CashuErrorException {
        PostSwapRequest postSwapRequest = new PostSwapRequest();

        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(160);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setProofs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask task = new VerifyProofsTask(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(FSVault.class);
        when(vault.retrieve(proof.getSecret().toString(), false)).thenReturn(proof.getUnblindedSignature().toString());

        when (mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("validate_amounts_error", exception.getMessage());
        }
    }

    @Test
    public void validateInvalidProof() throws CashuErrorException {
        var mint = spy(new Mint());
        PostSwapRequest postSwapRequest = new PostSwapRequest();

        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b5a8"));
        proof.setAmount(16);
        proof.setKeySetId("004cf8cba2f93266");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setProofs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        VerifyProofsTask task = new VerifyProofsTask(mint, postSwapRequest);

        var vault = Mockito.mock(FSVault.class);
        when(vault.retrieve(proof.getSecret().toString(), false)).thenReturn(proof.getUnblindedSignature().toString());

        when (mint.getKeySets()).thenReturn(Set.of(new KeySet("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("verify_proof_failed_error", exception.getMessage());
        }
    }

    @Test
    public void validateProofNotFound() throws CashuErrorException {
        PostSwapRequest postSwapRequest = new PostSwapRequest();

        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703"));
        proof.setSecret(Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518"));
        proof.setAmount(16);
        proof.setKeySetId("fake_ks_id");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        postSwapRequest.setProofs(List.of(proof));
        postSwapRequest.setBlindedMessages(List.of(blindedMessage));

        var mintDto = spy(vaultUtil.getMint());

        VerifyProofsTask task = new VerifyProofsTask(MintDto.toMint(mintDto), postSwapRequest);

        var vault = Mockito.mock(FSVault.class);
        when(vault.retrieve(proof.getSecret().toString(), false)).thenReturn(proof.getUnblindedSignature().toString());

        when (mintDto.getKeySets()).thenReturn(Set.of(new KeySetDto("004cf8cba2f93266", "sat", null)));

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("verify_proof_key_set_not_found:" + proof.getKeySetId(), exception.getMessage());
        }
    }
}
