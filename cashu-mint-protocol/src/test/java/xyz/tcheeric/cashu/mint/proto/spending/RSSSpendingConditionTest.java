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

    @Test
    public void verifySuccess() throws CashuErrorException {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
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

    /**
     * Ensures a proof already SPENT in the vault triggers a reuse error.
     */
    @Test
    public void verifyUsedProof() throws CashuErrorException {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint, service, proofVaultService);

        ProofEntity spent = new ProofEntity();
        spent.setState(ProofEntity.STATE_SPENT);
        Mockito.when(proofVaultService.retrieveProof(anyString())).thenReturn(spent);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            assertThrows(CashuErrorException.class, () -> cond.verify(proof));
        }
    }

    /**
     * Verifies that a PENDING proof is NOT treated as terminal already-used.
     * The downstream InvalidateProofsTask handles idempotent recovery from
     * PENDING (see storeAndInvalidateIdempotent). Rejecting here would block
     * legitimate saga retries.
     */
    @Test
    public void verifyPendingProofPassesThrough() throws CashuErrorException {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint, service, proofVaultService);

        ProofEntity pending = new ProofEntity();
        pending.setState(ProofEntity.STATE_PENDING);
        Mockito.when(proofVaultService.retrieveProof(anyString())).thenReturn(pending);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            // Must not throw verify_proof_already_used_error — the rest of
            // verify() runs and reaches BDHKE verification (mocked to true).
            cond.verify(proof);
        }
    }
}
