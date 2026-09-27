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
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.CachedSwapResponse;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHold;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.ports.SwapResponseCache;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultServiceMocks;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.ProofLockManager;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Issue #519, through {@link SwapTask}: the replay reaches this instance while its original holds
 * the inputs on another, so the vault refuses the claim with {@code proofs_not_bound}.
 *
 * <p>The "other instance" is modelled by the shared hold store and response cache: it has recorded
 * a hold under the same request fingerprint, and it stores its response part-way through this
 * instance's wait.
 */
class SwapTaskReplayRacingOriginalTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final String KEYSET_ID = "0123456789abcdef";
    private static final String OTHER_INSTANCE_HOLD = "swap-other-instance";

    private final SharedCache cache = new SharedCache();
    private final SharedHolds holds = new SharedHolds();
    private final List<Long> pausesTaken = new ArrayList<>();
    private int claimAttempts;

    // Acceptance of #519: a replay arriving at a second instance while the original is in flight
    // returns the original response, not an error.
    @Test
    void aReplayRacingItsOriginalOnAnotherInstanceReturnsTheOriginalResponse() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = request("aa01");
        holds.recordOriginal(SwapRequestFingerprint.of(request), SwapHoldPhase.SIGNING);
        PostSwapResponse originalResponse = originalResponse();

        PostSwapResponse answer = executeSwap(request,
                waitWhereTheOriginalAnswersAtPause(2, request, originalResponse));

        assertThat(answer.getBlindSignatures()).isEqualTo(originalResponse.getBlindSignatures());
        assertThat(claimAttempts).as("the replay did try, and was refused by the original's hold")
                .isOne();
        assertThat(pausesTaken).hasSize(2);
    }

    // Acceptance of #519: a different request whose inputs are held by another swap is still
    // refused, and refused without waiting.
    @Test
    void aDifferentRequestWhoseInputsAreHeldIsRefusedWithoutWaiting() {
        PostSwapRequest<RandomStringSecret> request = request("aa01");
        holds.recordOriginal(SwapRequestFingerprint.of(request("bb02")), SwapHoldPhase.SIGNING);

        assertThatThrownBy(() -> executeSwap(request, waitNeverAnswering()))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.proofs_not_bound);
        assertThat(pausesTaken).as("nothing of this request is in flight, so no wait").isEmpty();
    }

    // An original that never answers leaves the replay with the refusal it met, once the budget
    // is spent.
    @Test
    void aReplayWhoseOriginalNeverAnswersIsRefusedOnceTheBudgetIsSpent() {
        PostSwapRequest<RandomStringSecret> request = request("aa01");
        holds.recordOriginal(SwapRequestFingerprint.of(request), SwapHoldPhase.SIGNING);

        assertThatThrownBy(() -> executeSwap(request, waitNeverAnswering()))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.proofs_not_bound);
        assertThat(pausesTaken).hasSize(3);
    }

    // The hold record is opened before the claim, under this request's fingerprint, so a replay
    // racing this swap on another instance can find it as soon as the inputs are bound.
    @Test
    void theHoldIsRecordedUnderTheRequestFingerprintBeforeTheInputsAreClaimed() {
        PostSwapRequest<RandomStringSecret> request = request("aa01");

        assertThatThrownBy(() -> executeSwap(request, waitNeverAnswering()))
                .isInstanceOf(CashuErrorException.class);

        assertThat(holds.openedBeforeClaim).isTrue();
        assertThat(holds.openedWith).isEqualTo(SwapRequestFingerprint.of(request));
    }

    // The wait happens after the input lock is released, so another request for the same inputs
    // on this instance is not queued behind a replay that is waiting on another instance.
    @Test
    void theInputLockIsReleasedWhileTheReplayWaits() throws Exception {
        PostSwapRequest<RandomStringSecret> request = request("aa01");
        holds.recordOriginal(SwapRequestFingerprint.of(request), SwapHoldPhase.SIGNING);
        List<String> secrets = request.getInputs().stream().map(p -> p.getSecret().toString()).toList();
        List<Boolean> lockFreeDuringWait = new ArrayList<>();
        InFlightReplayWait wait = new InFlightReplayWait(pauses(), Duration.ofSeconds(2), fixedClock(),
                millis -> lockFreeDuringWait.add(lockIsFreeFromAnotherThread(secrets)));

        assertThatThrownBy(() -> executeSwap(request, wait)).isInstanceOf(CashuErrorException.class);

        assertThat(lockFreeDuringWait).isNotEmpty().containsOnly(true);
    }

    private static boolean lockIsFreeFromAnotherThread(List<String> secrets) throws InterruptedException {
        CompletableFuture<Boolean> acquired = CompletableFuture
                .supplyAsync(() -> {
                    try (ProofLockManager.ProofLock ignored = ProofLockManager.lockSecrets(secrets)) {
                        return true;
                    }
                });
        try {
            return acquired.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    // --- harness ---------------------------------------------------------------------------

    private InFlightReplayWait waitWhereTheOriginalAnswersAtPause(int pauseNumber,
                                                                  PostSwapRequest<RandomStringSecret> request,
                                                                  PostSwapResponse response) {
        SwapResponseReplay otherInstance = SwapResponseReplay.of(request, cache, holds);
        return new InFlightReplayWait(pauses(), Duration.ofSeconds(2), fixedClock(), millis -> {
            pausesTaken.add(millis);
            if (pausesTaken.size() == pauseNumber) {
                otherInstance.remember(response);
            }
        });
    }

    private InFlightReplayWait waitNeverAnswering() {
        return new InFlightReplayWait(pauses(), Duration.ofSeconds(2), fixedClock(), pausesTaken::add);
    }

    private static List<Duration> pauses() {
        return List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private PostSwapResponse executeSwap(PostSwapRequest<RandomStringSecret> request, InFlightReplayWait wait)
            throws CashuErrorException {
        MintVaultService mintVault = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVault.retrieveMint(anyString())).thenReturn(new MintEntity());

        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(
                     VerifyProofsTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(
                     VerifyFeesTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<ValidateTransactionTask> validateCons = Mockito.mockConstruction(
                     ValidateTransactionTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute())) {

            factory.when(MintProtocolServiceFactory::getInstance)
                    .thenReturn(Mockito.mock(MintProtocolService.class));

            return new SwapTask<>(UUID.randomUUID(), request, mintLoadService(),
                    new DefaultSignatureVaultService(), mintVault, proofVaultRefusingEveryClaim(),
                    holds, cache, wait).execute();
        }
    }

    /** The inputs are bound to the other instance's hold, so this instance can claim none. */
    private ProofVaultService proofVaultRefusingEveryClaim() throws CashuErrorException {
        ProofVaultService proofVault = ProofVaultServiceMocks.keyingProofsByIssuanceKey();
        Mockito.when(proofVault.insertOrClaimForHold(any(), anyString(), any())).thenAnswer(invocation -> {
            claimAttempts++;
            holds.claimed = true;
            return 0;
        });
        return proofVault;
    }

    private static MintLoadService mintLoadService() throws CashuErrorException {
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").build());
        MintLoadService loadService = Mockito.mock(MintLoadService.class);
        Mockito.when(loadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);
        return loadService;
    }

    private static PostSwapResponse originalResponse() {
        return new PostSwapResponse(List.of(new BlindSignature(8, KeysetId.fromString(KEYSET_ID),
                SignatureTestData.sampleSignature(), null)));
    }

    private static PostSwapRequest<RandomStringSecret> request(String secret) {
        RSSProof input = new RSSProof();
        input.setAmount(8);
        input.setKeySetId(KEYSET_ID);
        input.setSecret(RandomStringSecret.fromString(secret));
        input.setUnblindedSignature(SignatureTestData.sampleSignature());

        BlindedMessage output = new BlindedMessage();
        output.setAmount(8);
        output.setKeySetId(KeysetId.fromString(KEYSET_ID));
        output.setBlindedMessage(PublicKey.fromString(
                "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.<Proof<RandomStringSecret>>of(input));
        request.setBlindedMessages(List.of(output));
        return request;
    }

    private record Hold(String holdId, String fingerprintHex, SwapHoldPhase phase, int inputCount,
                        Instant createdAt, Instant updatedAt) implements SwapHold {
    }

    /** The hold table both instances share. */
    private static final class SharedHolds implements SwapHoldRepository {

        private final List<Hold> holds = new ArrayList<>();
        private boolean claimed;
        private boolean openedBeforeClaim;
        private SwapRequestFingerprint openedWith;

        void recordOriginal(SwapRequestFingerprint fingerprint, SwapHoldPhase phase) {
            holds.add(new Hold(OTHER_INSTANCE_HOLD, fingerprint.hex(), phase, 1, NOW, NOW));
        }

        @Override
        public void open(String holdId, int inputCount, SwapRequestFingerprint requestFingerprint) {
            openedBeforeClaim = !claimed;
            openedWith = requestFingerprint;
            holds.add(new Hold(holdId, requestFingerprint.hex(), SwapHoldPhase.HELD, inputCount, NOW, NOW));
        }

        @Override
        public void advance(String holdId, SwapHoldPhase phase) {
            holds.replaceAll(hold -> hold.holdId().equals(holdId)
                    ? new Hold(holdId, hold.fingerprintHex(), phase, hold.inputCount(), hold.createdAt(), NOW)
                    : hold);
        }

        @Override
        public List<SwapHold> findByRequestFingerprint(SwapRequestFingerprint requestFingerprint) {
            return holds.stream()
                    .filter(hold -> hold.fingerprintHex().equals(requestFingerprint.hex()))
                    .map(SwapHold.class::cast)
                    .toList();
        }
    }

    /** The response cache both instances share. */
    private static final class SharedCache implements SwapResponseCache {

        private final Map<String, String> entries = new HashMap<>();

        @Override
        public Optional<CachedSwapResponse> find(SwapRequestFingerprint fingerprint) {
            return Optional.ofNullable(entries.get(fingerprint.hex()))
                    .map(json -> new Stored(fingerprint.hex(), json));
        }

        @Override
        public void store(SwapRequestFingerprint fingerprint, String responseJson) {
            entries.put(fingerprint.hex(), responseJson);
        }
    }

    private record Stored(String requestFingerprint, String responseJson) implements CachedSwapResponse {
        @Override
        public Instant expiresAt() {
            return Instant.MAX;
        }
    }
}
