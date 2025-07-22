package xyz.tcheeric.test.protocol.tasks;

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
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.tasks.InvalidateProofsTask;
import xyz.tcheeric.cashu.vault.api.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.api.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


public class InvalidateProofTest {

    private Mint mint;

    @BeforeEach
    public void setUp() throws CashuErrorException {
        this.mint = new Mint(UUID.randomUUID().toString());

        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");

        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, "0392810a73efd77346d3658bf0dc7004fae1e201a03bd511d8077956d7785a8355" , Utils.bytesToHexString(hashToCurveSecret));
        DBProofVault proofVault = new DBProofVault(proofConfiguration);

        proofVault.storePending();
    }

    @AfterEach
    public void tearDown() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");

        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, "0392810a73efd77346d3658bf0dc7004fae1e201a03bd511d8077956d7785a8355" , Utils.bytesToHexString(hashToCurveSecret));
        DBProofVault proofVault = new DBProofVault(proofConfiguration);

        DBMintVault mintVault = new DBMintVault(mintConfiguration);
        mintVault.delete();
    }

    @Test
    public void invalidateProof() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest();

        RSSProof proof = new RSSProof();
        proof.setUnblindedSignature(Signature.fromString("0392810a73efd77346d3658bf0dc7004fae1e201a03bd511d8077956d7785a8355"));
        proof.setSecret(RandomStringSecret.fromString("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1"));
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

        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        ProofConfiguration config = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), Utils.bytesToHexString(hashToCurveSecret));
        DBProofVault vault = new DBProofVault(config);
        String strProof = vault.retrieve(false);

        assertNotNull(strProof);
        assertEquals(proof.getUnblindedSignature().toString(), strProof);
    }
}
