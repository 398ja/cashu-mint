package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherRedemptionCapMode;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultServiceMocks;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherRedemptionCap;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * cashu-mint#540: a redemption claim is given back exactly when the swap that made it signed
 * nothing, and kept as soon as any output was signed.
 *
 * <p>The IT {@code aSwapRefusedAfterItsClaimGivesTheClaimBack} cannot pin this: its refusal
 * ({@code outputs_already_signed} from {@code ValidateTransactionTask}) happens before the claim,
 * so it passes with the give-back deleted. Here the refusal comes from signing itself, after the
 * claim, which is the only place a give-back can matter.
 */
class SwapTaskRedemptionGiveBackTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final String TOKEN = "unit-redeemer";
    private static final List<String> BLINDED_MESSAGES = List.of(
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7");

    @Test
    void aClaimIsGivenBackWhenSigningFailsBeforeAnyOutputIsSigned() throws Exception {
        CountingLedger ledger = new CountingLedger(16L);
        UUID voucherId = UUID.randomUUID();

        assertThatThrownBy(() -> executeRedemption(ledger, voucherId, 0))
                .isInstanceOf(CashuErrorException.class);

        assertThat(ledger.claims.get()).as("the claim was made").isEqualTo(1);
        assertThat(ledger.redeemed.get()).as("nothing was signed, so the claim is given back").isZero();
    }

    @Test
    void aClaimIsKeptWhenSigningFailsAfterAnOutputIsSigned() throws Exception {
        CountingLedger ledger = new CountingLedger(16L);
        UUID voucherId = UUID.randomUUID();

        assertThatThrownBy(() -> executeRedemption(ledger, voucherId, 1))
                .isInstanceOf(CashuErrorException.class);

        assertThat(ledger.redeemed.get())
                .as("an output is signed and the inputs are spent, so the claim must stand")
                .isEqualTo(16L);
    }

    @Test
    void aClaimIsKeptWhenTheSwapSucceeds() throws Exception {
        CountingLedger ledger = new CountingLedger(16L);
        executeRedemption(ledger, UUID.randomUUID(), Integer.MAX_VALUE);
        assertThat(ledger.redeemed.get()).isEqualTo(16L);
    }

    /**
     * Review §2b: the claim is taken only while this request holds the inputs. A request whose
     * inputs are held by another (a racing replay of the original on another instance) is refused
     * by the hold with {@code proofs_not_bound}, which makes it wait for the original, and never
     * reaches the cap. Claiming first would answer it 90025 for a swap that succeeded.
     */
    @Test
    void noClaimIsMadeWhenTheInputsCannotBeHeld() throws Exception {
        CountingLedger ledger = new CountingLedger(16L);
        holdsBound = 0;

        assertThatThrownBy(() -> executeRedemption(ledger, UUID.randomUUID(), Integer.MAX_VALUE))
                .isInstanceOf(CashuErrorException.class)
                .satisfies(e -> assertThat(((CashuErrorException) e).getErrorCode())
                        .isEqualTo(CashuErrorCode.proofs_not_bound));

        assertThat(ledger.claims.get()).as("the cap is never consulted without the inputs held").isZero();
    }

    /**
     * Re-review: log mode must never refuse a swap. A ledger that fails (database outage, lock
     * timeout, a node whose voucher_redemption_claim migration has not run) must not turn a credited
     * swap into an outage in log. In enforce it still refuses (an unknown claim).
     */
    @Test
    void logModeDoesNotRefuseASwapWhenTheLedgerFails() throws Exception {
        capMode = VoucherRedemptionCapMode.LOG;
        executeRedemption(brokenLedger(), UUID.randomUUID(), Integer.MAX_VALUE);
    }

    @Test
    void enforceStillRefusesWhenTheLedgerFails() {
        assertThatThrownBy(() -> executeRedemption(brokenLedger(), UUID.randomUUID(), Integer.MAX_VALUE))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }

    private static CountingLedger brokenLedger() {
        return new CountingLedger(16L) {
            @Override
            public synchronized Claim claim(ClaimRequest request) {
                throw new org.springframework.dao.DataAccessResourceFailureException("ledger down");
            }
        };
    }

    private VoucherRedemptionCapMode capMode = VoucherRedemptionCapMode.ENFORCE;

    /** How many inputs the vault binds to a hold; -1 binds them all. */
    private int holdsBound = -1;

    // --- harness ---------------------------------------------------------------------------

    /** An in-memory ledger with the adapter's semantics, for one voucher. */
    private static class CountingLedger implements VoucherRedemptionLedger {
        final AtomicLong redeemed = new AtomicLong();
        final AtomicInteger claims = new AtomicInteger();
        final java.util.Map<String, Long> standing = new java.util.HashMap<>();
        final long cap;

        CountingLedger(long cap) {
            this.cap = cap;
        }

        @Override
        public synchronized Claim claim(ClaimRequest request) {
            claims.incrementAndGet();
            if (standing.containsKey(request.requestFingerprint())) {
                return new Claim(ClaimOutcome.REUSED, redeemed.get(), cap, false);
            }
            if (request.refuseOverCap() && redeemed.get() + request.sats() > cap) {
                return new Claim(ClaimOutcome.REFUSED_OVER_CAP, redeemed.get(), cap, false);
            }
            standing.put(request.requestFingerprint(), request.sats());
            return new Claim(ClaimOutcome.ACCEPTED, redeemed.addAndGet(request.sats()), cap, false);
        }

        @Override
        public synchronized boolean giveBack(String requestFingerprint) {
            Long sats = standing.remove(requestFingerprint);
            if (sats == null) {
                return false;
            }
            redeemed.set(Math.max(0, redeemed.get() - sats));
            return true;
        }

        @Override
        public int giveBackForHold(String holdId) {
            return 0;
        }

        @Override
        public Release release(String requestFingerprint, String voucherId, String redeemerDigest, Long sats) {
            return new Release(ReleaseOutcome.NOT_FOUND, 0, 0);
        }
    }

    private static final class NoopHolds implements SwapHoldRepository {
        @Override
        public void open(String holdId, int inputCount, SwapRequestFingerprint requestFingerprint) {
        }

        @Override
        public void advance(String holdId, SwapHoldPhase phase) {
        }
    }

    /**
     * @param signaturesBeforeRefusal how many outputs sign before the next is refused
     */
    private void executeRedemption(CountingLedger ledger, UUID voucherId, int signaturesBeforeRefusal)
            throws CashuErrorException {
        ProofVaultService proofVault = ProofVaultServiceMocks.keyingProofsByIssuanceKey();
        Mockito.when(proofVault.insertOrClaimForHold(any(), anyString(), any()))
                .thenAnswer(invocation -> holdsBound >= 0 ? holdsBound : ((List<?>) invocation.getArgument(0)).size());
        Mockito.when(proofVault.commitSpentForHold(anyString())).thenReturn(1);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        MintVaultService mintVault = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVault.retrieveMint(anyString())).thenReturn(new MintEntity());
        AtomicInteger signed = new AtomicInteger();

        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(
                     VerifyProofsTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(
                     VerifyFeesTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<ValidateTransactionTask> validateCons = Mockito.mockConstruction(
                     ValidateTransactionTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                     SignBlindedMessageTask.class, (mock, ctx) -> {
                         BlindedMessage output = (BlindedMessage) ctx.arguments().get(1);
                         Mockito.doAnswer(invocation -> {
                             if (signed.getAndIncrement() >= signaturesBeforeRefusal) {
                                 throw new CashuErrorException(CashuErrorCode.outputs_already_signed);
                             }
                             return new BlindSignature(output.getAmount(),
                                     KeysetId.fromString(KEYSET_ID),
                                     SignatureTestData.sampleSignature(), null);
                         }).when(mock).execute();
                     })) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            new SwapTask<>(UUID.randomUUID(), voucherRequest(voucherId), mintLoadService(),
                    Mockito.mock(SignatureVaultService.class), mintVault, proofVault, new NoopHolds())
                    .withRedemptionCap(new VoucherRedemptionCap(capMode, ledger,
                            List.of(TOKEN)))
                    .declaringRedemption(voucherId + ":16", TOKEN)
                    .execute();
        }
    }

    private MintLoadService mintLoadService() throws CashuErrorException {
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").build());
        MintLoadService loadService = Mockito.mock(MintLoadService.class);
        Mockito.when(loadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);
        return loadService;
    }

    private PostSwapRequest<VoucherSecret> voucherRequest(UUID voucherId) {
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(voucherId)
                .issuerId("test-merchant")
                .unit("sat")
                .faceValue(16L)
                .backingStrategy("PROPORTIONAL")
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();
        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(16);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(secret);
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());

        List<BlindedMessage> outputs = new ArrayList<>();
        for (String blindedMessage : BLINDED_MESSAGES) {
            BlindedMessage output = new BlindedMessage();
            output.setAmount(8);
            output.setKeySetId(KeysetId.fromString(KEYSET_ID));
            output.setBlindedMessage(PublicKey.fromString(blindedMessage));
            outputs.add(output);
        }
        PostSwapRequest<VoucherSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof));
        request.setBlindedMessages(outputs);
        return request;
    }
}
