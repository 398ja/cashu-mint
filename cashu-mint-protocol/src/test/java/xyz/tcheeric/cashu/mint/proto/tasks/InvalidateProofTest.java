package xyz.tcheeric.cashu.mint.proto.tasks;

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
import xyz.tcheeric.cashu.mint.proto.util.TestVaultUtil;
import xyz.tcheeric.cashu.mint.proto.vault.ProofRepository;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil.createRandomBytes;

public class InvalidateProofTest {

    private Mint mint;

    @BeforeEach
    public void setUp() {
        this.mint = new Mint(UUID.randomUUID().toString());
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

        ProofRepository repo = Mockito.mock(ProofRepository.class);
        TestVaultUtil.mockStore(repo);
        TestVaultUtil.mockInvalidate(repo);

        InvalidateProofsTask task = new InvalidateProofsTask(mint, request.getInputs(), repo);

        task.execute();

        verify(repo).store(Mockito.any());
        verify(repo).invalidate(Mockito.any());
    }
}
