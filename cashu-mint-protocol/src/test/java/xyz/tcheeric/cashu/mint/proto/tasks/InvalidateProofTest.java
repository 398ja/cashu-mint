package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil.createRandomBytes;

public class InvalidateProofTest {

    private Mint mint;

    @BeforeEach
    public void setUp() {
        this.mint = new Mint(UUID.randomUUID().toString());

        DBMintVault mintVault = new DBMintVault(MintProtocolUtil.toMintEntity(mint));
        mintVault.store();
    }

    @AfterEach
    public void tearDown() {
        DBMintVault mintVault = new DBMintVault(MintProtocolUtil.toMintEntity(mint));
        mintVault.delete();
    }

    @Test
    public void invalidateProof() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest();

        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString(createRandomBytes(33)));
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(256);
        proof.setKeySetId("00c4a3dade22f81b");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(256);
        blindedMessage.setKeySetId("00c4a3dade22f81b");
        blindedMessage.setBlindedMessage(PublicKey.fromBytes(BDHKEUtils.blindMessage(proof.getSecret().getBytes())[0]));

        request.setInputs(List.of(proof));
        request.setBlindedMessages(List.of(blindedMessage));

        InvalidateProofsTask task = new InvalidateProofsTask(mint, request.getInputs());

        task.execute();

        DBProofVault vault = DBProofVault.retrieveProof(mint.getId(), proof.getSecret().toString());

        assertNotNull(vault.getEntity());
        assertEquals(proof.getUnblindedSignature().toString(), vault.getEntity().getUnblindedSignature());
    }

    @Test
    public void invalidateProofFailure() {
        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString(createRandomBytes(33)));
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(1);
        proof.setKeySetId("00c4a3dade22f81b");

        InvalidateProofsTask<RandomStringSecret> task = new InvalidateProofsTask<>(mint, List.of(proof));

        try (MockedConstruction<DBProofVault> cons = Mockito.mockConstruction(DBProofVault.class,
                (mock, ctx) -> {
                    Mockito.doNothing().when(mock).store();
                    Mockito.doThrow(new CashuErrorException("fail")).when(mock).invalidate();
                })) {
            assertThrows(RuntimeException.class, task::execute);
        }
    }
}
