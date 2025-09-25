package xyz.tcheeric.cashu.mint.proto.spending;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

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
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

/*
    @Test
    public void verifySuccess() throws CashuErrorException {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint, service);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class);
             MockedConstruction<DBProofVault> vault = Mockito.mockConstruction(DBProofVault.class,
                     (mock, context) -> Mockito.when(mock.retrieveProof(anyString())).thenReturn(mock))) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            assertDoesNotThrow(() -> cond.verify(proof));
        }
    }
*/

    // Ensures verification succeeds even when keyset identifiers differ in letter casing.
    @Test
    public void verifySuccess() throws CashuErrorException {
        String kid = "ks1";
        Mint mint = createMint(kid.toUpperCase());
        RSSProof proof = createProof(kid.toLowerCase());
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint, service, proofVaultService);

        Mockito.when(proofVaultService.retrieveProof(anyString())).thenReturn(null);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            assertDoesNotThrow(() -> cond.verify(proof));
        }
    }

    // Ensures verification tolerates keyset identifiers wrapped in additional metadata.
    @Test
    public void verifySuccessWithDecoratedKeysetId() throws CashuErrorException {
        String kid = "00e3372e61d05605";
        Mint mint = createMint("KeysetId(value=" + kid.toUpperCase(Locale.ROOT) + ")");
        RSSProof proof = createProof(kid.toLowerCase(Locale.ROOT));
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint, service, proofVaultService);

        Mockito.when(proofVaultService.retrieveProof(anyString())).thenReturn(null);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            assertDoesNotThrow(() -> cond.verify(proof));
        }
    }

    // Ensures a proof already stored in the vault triggers a reuse error.
    @Test
    public void verifyUsedProof() throws CashuErrorException {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint, service, proofVaultService);

        Mockito.when(proofVaultService.retrieveProof(anyString())).thenReturn(new ProofEntity());

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            assertThrows(CashuErrorException.class, () -> cond.verify(proof));
        }
    }
}
