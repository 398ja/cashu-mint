package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultServiceMocks;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The hold is the swap's double-spend check, so it must still tell a wallet why it refused.
 *
 * <p>The per-input vault lookup in {@code RSSSpendingCondition} used to answer NUT-00
 * {@code 11001} for a spent input. It was removed because the hold's atomic claim already refuses
 * a spent input; these tests pin that the refusal keeps the same code, and that a refusal for any
 * other reason keeps {@code proofs_not_bound}.
 */
@DisplayName("SwapProofHold refusals")
class SwapProofHoldReportsSpentInputTest {

    private static final UUID MINT_ID = UUID.fromString("5a0f5b56-4b0a-4b3f-9a5a-1e5b9a1c3d2f");

    // A spent input refuses the claim, and the wallet hears verify_proof_already_used_error.
    @Test
    @DisplayName("a spent input is reported as already used (11001)")
    void spentInputIsReportedAsAlreadyUsed() throws CashuErrorException {
        ProofVaultService vault = vaultThatBinds(0);
        Mockito.when(vault.recordedProofs(any(), anyCollection())).thenReturn(List.of(row(ProofEntity.STATE_SPENT)));

        assertThatThrownBy(() -> hold(vault).claim(List.of(proof())))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.verify_proof_already_used_error));
        Mockito.verify(vault).refundForHold(anyString());
    }

    // An input held by a concurrent swap or melt is not spent, so the code stays proofs_not_bound.
    @Test
    @DisplayName("an input held elsewhere stays proofs_not_bound")
    void heldInputStaysNotBound() throws CashuErrorException {
        ProofVaultService vault = vaultThatBinds(0);
        Mockito.when(vault.recordedProofs(any(), anyCollection())).thenReturn(List.of(row(ProofEntity.STATE_PENDING)));

        assertThatThrownBy(() -> hold(vault).claim(List.of(proof())))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.proofs_not_bound));
    }

    // A failed classification lookup must not change the refusal into something else.
    @Test
    @DisplayName("a failed lookup on the refusal path stays proofs_not_bound")
    void failedLookupStaysNotBound() throws CashuErrorException {
        ProofVaultService vault = vaultThatBinds(0);
        Mockito.when(vault.recordedProofs(any(), anyCollection())).thenThrow(new IllegalStateException("vault down"));

        assertThatThrownBy(() -> hold(vault).claim(List.of(proof())))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.proofs_not_bound));
    }

    // The happy path never pays for the classification lookup.
    @Test
    @DisplayName("a successful claim does not look states up")
    void successfulClaimDoesNotLookStatesUp() throws CashuErrorException {
        ProofVaultService vault = vaultThatBinds(1);

        hold(vault).claim(List.of(proof()));

        Mockito.verify(vault, Mockito.never()).recordedProofs(any(), anyCollection());
    }

    // Keys come from one batch call, and the claimed row carries the key that batch chose.
    @Test
    @DisplayName("hold rows are keyed from one batch call")
    void rowsAreKeyedFromOneBatch() throws CashuErrorException {
        ProofVaultService vault = vaultThatBinds(2);
        StorageKey legacy = StorageKey.of("02" + "ab".repeat(32));
        RSSProof first = proof();
        RSSProof second = proof();
        Mockito.when(vault.storageKeysFor(any(), anyCollection())).thenAnswer(call -> {
            Collection<ProofSecret> secrets = call.getArgument(1);
            java.util.Map<ProofSecret, StorageKey> keys = new java.util.LinkedHashMap<>();
            for (ProofSecret secret : secrets) {
                keys.put(secret, secret.equals(ProofSecret.of(first.getSecret()))
                        ? legacy : SpentProofKey.issuanceKey(secret));
            }
            return keys;
        });

        hold(vault).claim(List.of(first, second));

        @SuppressWarnings("unchecked")
        var rows = org.mockito.ArgumentCaptor.forClass((Class<List<ProofEntity>>) (Class<?>) List.class);
        Mockito.verify(vault).insertOrClaimForHold(rows.capture(), anyString(), any());
        assertThat(rows.getValue()).extracting(ProofEntity::getSecret).containsExactly(
                legacy.hex(), SpentProofKey.issuanceKey(ProofSecret.of(second.getSecret())).hex());
        Mockito.verify(vault, Mockito.times(1)).storageKeysFor(any(), anyCollection());
        Mockito.verify(vault, Mockito.never()).storageKeyFor(any(), any());
    }

    // A spent voucher input is refused by the hold as already used, like a random-secret one.
    // Vouchers lost their own pre-check in the condition; this is what replaces it.
    @Test
    @DisplayName("a spent voucher input is reported as already used (11001)")
    void spentVoucherInputIsReportedAsAlreadyUsed() throws CashuErrorException {
        ProofVaultService vault = vaultThatBinds(0);
        Mockito.when(vault.recordedProofs(any(), anyCollection())).thenReturn(List.of(row(ProofEntity.STATE_SPENT)));

        assertThatThrownBy(() -> hold(vault).claim(List.of(voucherProof())))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.verify_proof_already_used_error));
    }

    // A by-reference (v2) voucher is held under its wire secret, never under the document it
    // names, so v1 and v2 spellings of one voucher are distinct proofs (cashu-voucher#64).
    @Test
    @DisplayName("a by-reference voucher is held under its wire secret")
    void voucherHoldIsKeyedOnTheWireSecret() throws CashuErrorException {
        ProofVaultService vault = vaultThatBinds(1);
        Proof<VoucherSecret> proof = voucherProof();

        hold(vault).claim(List.of(proof));

        @SuppressWarnings("unchecked")
        var secrets = org.mockito.ArgumentCaptor.forClass((Class<Collection<ProofSecret>>) (Class<?>) Collection.class);
        Mockito.verify(vault).storageKeysFor(any(), secrets.capture());
        assertThat(secrets.getValue()).containsExactly(ProofSecret.of(proof.getSecret()));
    }

    private static Proof<VoucherSecret> voucherProof() {
        String wire = "[\"VOUCHER\",{\"nonce\":\"" + "cd".repeat(16) + "\",\"data\":\"" + "ef".repeat(32)
                + "\",\"tags\":[]}]";
        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(8);
        proof.setKeySetId("0123456789abcdef");
        proof.setSecret((VoucherSecret) SecretUtil.toSecret(wire));
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    private static ProofVaultService vaultThatBinds(int bound) throws CashuErrorException {
        ProofVaultService vault = ProofVaultServiceMocks.keyingProofsByIssuanceKey();
        Mockito.when(vault.insertOrClaimForHold(any(), anyString(), any())).thenReturn(bound);
        return vault;
    }

    private static SwapProofHold hold(ProofVaultService vault) throws CashuErrorException {
        MintVaultService mints = Mockito.mock(MintVaultService.class);
        Mockito.when(mints.retrieveMint(anyString())).thenReturn(new MintEntity());
        SwapHoldRepository holds = new SwapHoldRepository() {
            @Override
            public void open(String holdId, int inputCount, SwapRequestFingerprint requestFingerprint) {
            }

            @Override
            public void advance(String holdId, SwapHoldPhase phase) {
            }
        };
        return new SwapProofHold(MINT_ID, mints, vault, holds, new SwapRequestFingerprint("ab".repeat(32)));
    }

    private static RSSProof proof() {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setKeySetId("0123456789abcdef");
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    private static ProofEntity row(String state) {
        ProofEntity row = new ProofEntity();
        row.setState(state);
        return row;
    }
}
