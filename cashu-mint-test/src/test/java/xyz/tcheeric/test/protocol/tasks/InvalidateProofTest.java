package xyz.tcheeric.test.protocol.tasks;

import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.Proof;
import cashu.common.model.PublicKey;
import cashu.common.model.Secret;
import cashu.common.model.Signature;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.util.CashuErrorException;
import cashu.crypto.BDHKEUtils;
import cashu.mint.proto.tasks.InvalidateProofsTask;
import cashu.util.Utils;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSMintVault;
import cashu.vault.impl.fs.FSProofVault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class InvalidateProofTest {

    private Mint mint;

    @Before
    public void setUp() throws CashuErrorException {
        this.mint = new Mint("d40a6717990b684fc35ff8a25e5b51830525894acd5501fd3f9ace5c30471baa");

        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");

        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, "0392810a73efd77346d3658bf0dc7004fae1e201a03bd511d8077956d7785a8355" , Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault proofVault = new FSProofVault(proofConfiguration);

        proofVault.storePending();
    }

    @After
    public void tearDown() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");

        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, "0392810a73efd77346d3658bf0dc7004fae1e201a03bd511d8077956d7785a8355" , Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault proofVault = new FSProofVault(proofConfiguration);

        //proofVault.deletePending();
        //proofVault.archive(proofConfiguration.getHashToCurveSecret());

        FSMintVault mintVault = new FSMintVault(mintConfiguration);
        mintVault.delete();
    }

    @Test
    public void invalidateProof() throws CashuErrorException {
        PostSwapRequest request = new PostSwapRequest();

        Proof proof = new Proof();
        proof.setUnblindedSignature(Signature.fromString("0392810a73efd77346d3658bf0dc7004fae1e201a03bd511d8077956d7785a8355"));
        proof.setSecret(Secret.fromString("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1"));
        proof.setAmount(256);
        proof.setKeySetId("00c4a3dade22f81b");

        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(256);
        blindedMessage.setKeySetId("00c4a3dade22f81b");
        blindedMessage.setBlindedMessage(PublicKey.fromBytes(BDHKEUtils.blindMessage(proof.getSecret().getBytes())[0]));

        request.setProofs(List.of(proof));
        request.setBlindedMessages(List.of(blindedMessage));

        InvalidateProofsTask task = new InvalidateProofsTask(mint, request.getProofs());

        task.execute();

        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve("eb3472ab308e71fbd503f88b6027e44717dd079e347bc6ac0ce1f3fc936bdbb1");
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        ProofConfiguration config = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault vault = new FSProofVault(config);
        String strProof = vault.retrieve(Utils.bytesToHexString(hashToCurveSecret), false);

        assertNotNull(strProof);
        assertEquals(proof.getUnblindedSignature().toString(), strProof);
    }
}
