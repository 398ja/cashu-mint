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
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
                     (mock, context) -> Mockito.when(mock.retrieveProof(any(), anyString())).thenReturn(mock))) {
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

        Mockito.when(proofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            assertDoesNotThrow(() -> cond.verify(proof));
        }
    }


    /**
     * A SPENT proof must be rejected using the mint id the condition was built with, and the
     * lookup must be scoped to exactly that mint.
     *
     * <p>Regression test for cashu-vault#153. The scoped lookup was introduced by resolving
     * {@code mint.getId()} into a UUID, and a wrong or absent mint id makes the lookup either miss
     * the row or read another mint's row. Pinning the argument is what distinguishes "the check
     * ran against this mint" from "the check ran".
     */

    /**
     * A condition holding a mint that cannot supply an id must refuse to verify rather than
     * verifying without a double-spend check.
     *
     * <p>Regression test for the bug this suite exists for. The mint id was originally resolved
     * with {@code UUID.fromString(mint.getId())} <em>inside</em> the try that treats a vault
     * failure as "no proof found". An unstubbed mint mock returns a null id, so the NPE was caught
     * and relabelled "proof not found" and every proof passed the double-spend check. The assertion
     * that the vault was never called is the part that makes this a guard test: a condition without
     * a mint has no lookup it is allowed to perform.
     */
    @Test
    public void verifyWithoutMintIdRefusesRatherThanSkippingTheDoubleSpendCheck() throws CashuErrorException {
        String kid = "ks1";
        RSSProof proof = createProof(kid);
        // An unstubbed mock returns null from getId(), which is exactly the shape that hid the bug.
        Mint mintWithoutId = Mockito.mock(Mint.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mintWithoutId, service, proofVaultService);

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            bdhke.when(() -> BDHKEUtils.verify(anyString(), ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any())).thenReturn(true);
            bdhke.when(() -> BDHKEUtils.hashToCurve(anyString())).thenReturn(new byte[32]);

            CashuErrorException ex =
                    assertThrows(CashuErrorException.class, () -> cond.verify(proof),
                            "Without a mint there is no double-spend check, so verification must "
                                    + "not succeed");
            assertTrue(ex.getMessage().contains("without a mint"),
                    "The refusal must name its cause; got: " + ex.getMessage());
        }
        Mockito.verify(proofVaultService, never()).retrieveProof(any(), any(ProofSecret.class));
    }

    /**
     * A condition cannot be built without a mint, a protocol service or a vault (#488).
     *
     * <p>The all-arguments constructor used to accept a null mint while the two-argument one
     * rejected it, so the class half-believed its own invariant and the double-spend guard was the
     * only line of defence. Now the state is unrepresentable.
     */
    @Test
    public void aConditionCannotBeBuiltWithoutItsCollaborators() {
        Mint mint = createMint("ks1");
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        assertThrows(NullPointerException.class,
                () -> new RSSSpendingCondition(null, service, proofVaultService));
        assertThrows(NullPointerException.class,
                () -> new RSSSpendingCondition(mint, null, proofVaultService));
        assertThrows(NullPointerException.class,
                () -> new RSSSpendingCondition(mint, service, null));
        assertThrows(NullPointerException.class, () -> new RSSSpendingCondition(null, service));
    }


    /**
     * The vault client's own unchecked failure, a {@code RestClientException}, is an outage too and
     * is absorbed like the checked one.
     *
     * <p>{@code ProofClient} talks to the vault through a {@code RestTemplate}, which reports a
     * refused connection or a 5xx as a {@code RestClientException} subtype.
     */

    /**
     * A programming error in the lookup is not mistaken for a vault outage (#488).
     *
     * <p>The catch used to be on {@code Exception}. In #486 an NPE from resolving the mint inside
     * it was relabelled "no proof found", and the double-spend check silently passed for every
     * proof. Only outage exceptions are absorbed now, so anything else stops verification.
     */


    /**
     * The condition never consults the vault: the swap's hold is the double-spend check
     * (SwapProofHoldReportsSpentInputTest), and a per-input lookup here cost one or two vault
     * round trips per proof on every swap.
     */
    @Test
    public void verifyDoesNotConsultTheVault() throws CashuErrorException {
        String kid = "ks1";
        Mint mint = createMint(kid);
        RSSProof proof = createProof(kid);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        RSSSpendingCondition cond = new RSSSpendingCondition(mint, service, proofVaultService);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        assertDoesNotThrow(() -> cond.verify(proof));
        Mockito.verifyNoInteractions(proofVaultService);
    }
}
