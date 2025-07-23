package xyz.tcheeric.test.protocol.spending;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

public class RSSSpendingConditionTest {

    private Mint createMint(String keysetId) {
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id(keysetId).unit("sat").build());
        return mint;
    }

    private RSSProof createProof(String keysetId) {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setKeySetId(keysetId);
        proof.setSecret(RandomStringSecret.fromString("abcd"));
        proof.setUnblindedSignature(Signature.fromString("02aa"));
        return proof;
    }

    @Test
    public void verifySuccess() throws Exception {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint);

        try (MockedStatic<MintProtocolUtil> util = Mockito.mockStatic(MintProtocolUtil.class);
             MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class);
             MockedConstruction<DBProofVault> vault = Mockito.mockConstruction(DBProofVault.class,
                     (mock, context) -> Mockito.when(mock.retrieve()).thenReturn(null))) {
            util.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), any(), any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(any())).thenReturn(new byte[32]);

            assertDoesNotThrow(() -> cond.verify(proof));
        }
    }

    @Test
    public void verifyUsedProof() throws Exception {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint);

        try (MockedStatic<MintProtocolUtil> util = Mockito.mockStatic(MintProtocolUtil.class);
             MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class);
             MockedConstruction<DBProofVault> vault = Mockito.mockConstruction(DBProofVault.class,
                     (mock, context) -> Mockito.when(mock.retrieve()).thenReturn("used"))) {
            util.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), any(), any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(any())).thenReturn(new byte[32]);

            assertThrows(CashuErrorException.class, () -> cond.verify(proof));
        }
    }
}
