package xyz.tcheeric.cashu.mint.proto.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

/**
 * Every proof the mint writes to the vault carries the keyset that signed it, so the vault can
 * book spent value per keyset (cashu-vault#156, #515).
 */
class MintProtocolUtilProofKeysetTest {

  private static final String KEYSET_ID = "00ad268c4d1f5826";

  @Test
  void toProofEntityCopiesTheProofKeysetId() {
    RSSProof proof = new RSSProof();
    proof.setAmount(8);
    proof.setKeySetId(KEYSET_ID);
    proof.setSecret(RandomStringSecret.create());
    proof.setUnblindedSignature(SignatureTestData.sampleSignature());
    MintEntity mint = new MintEntity();
    mint.setId(UUID.randomUUID());

    ProofEntity entity = MintProtocolUtil.toProofEntity(proof, mint);

    assertEquals(KEYSET_ID, entity.getKeysetId());
  }
}
