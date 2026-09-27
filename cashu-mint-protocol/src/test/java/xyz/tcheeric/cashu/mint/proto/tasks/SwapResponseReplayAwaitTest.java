package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.CachedSwapResponse;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHold;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.ports.SwapResponseCache;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #519: a swap replay refused on one instance while its original is still in flight on
 * another waits for the original's response instead of failing.
 *
 * <p>The sleeper is fake, so no test really waits: each pause is recorded, and the test decides at
 * which pause the original "finishes" and stores its response.
 */
class SwapResponseReplayAwaitTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final String OWN_HOLD = "swap-own";
    private static final String ORIGINAL_HOLD = "swap-original";
    private static final String KEYSET_ID = "0123456789abcdef";

    private final FakeCache cache = new FakeCache();
    private final FakeHolds holds = new FakeHolds();
    private final List<Long> pausesTaken = new ArrayList<>();
    private final PostSwapRequest<RandomStringSecret> request = request();
    private final SwapRequestFingerprint fingerprint = SwapRequestFingerprint.of(request);
    private final SwapResponseReplay replay = SwapResponseReplay.of(request, cache, holds);

    // The race the issue describes: the original holds the inputs on another instance, and its
    // response lands while the replay is waiting. The replay is answered with it.
    @Test
    void aReplayRacingItsOriginalIsAnsweredWithTheOriginalsResponse() throws CashuErrorException {
        holds.add(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.SIGNING, NOW));
        PostSwapResponse original = response();
        int arrivesAtPause = 3;

        Optional<PostSwapResponse> answer = replay.awaitInFlightOriginal(OWN_HOLD,
                waitStoringAt(arrivesAtPause, original));

        assertThat(answer).as("the replay gets the original's signatures").isPresent();
        assertThat(answer.get().getBlindSignatures()).isEqualTo(original.getBlindSignatures());
        assertThat(pausesTaken).as("it stops waiting as soon as the response is there")
                .hasSize(arrivesAtPause);
    }

    // A different request whose inputs are held by someone else is not waited for at all: the
    // refusal it met is the right answer, and making it wait would only slow the error down.
    @Test
    void aDifferentRequestIsNotKeptWaiting() throws CashuErrorException {
        SwapRequestFingerprint someoneElse = new SwapRequestFingerprint("cd".repeat(32));
        holds.add(hold(ORIGINAL_HOLD, someoneElse, SwapHoldPhase.SIGNING, NOW));

        Optional<PostSwapResponse> answer = replay.awaitInFlightOriginal(OWN_HOLD, waitNeverStoring());

        assertThat(answer).isEmpty();
        assertThat(pausesTaken).as("no pause for a request that is not in flight").isEmpty();
    }

    // The attempt's own hold is not an original: a request refused by its own claim has nothing
    // in flight to wait for.
    @Test
    void theAttemptsOwnHoldIsNotMistakenForAnOriginal() throws CashuErrorException {
        holds.add(hold(OWN_HOLD, fingerprint, SwapHoldPhase.HELD, NOW));

        assertThat(replay.awaitInFlightOriginal(OWN_HOLD, waitNeverStoring())).isEmpty();
        assertThat(pausesTaken).isEmpty();
    }

    // An original that was released signed nothing, so it has no response to wait for.
    @Test
    void aReleasedOriginalIsNotWaitedFor() throws CashuErrorException {
        holds.add(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.RELEASED, NOW));

        assertThat(replay.awaitInFlightOriginal(OWN_HOLD, waitNeverStoring())).isEmpty();
        assertThat(pausesTaken).isEmpty();
    }

    // A just-committed original is about to store its response, so it is still worth waiting for.
    @Test
    void aJustCommittedOriginalIsStillWaitedFor() throws CashuErrorException {
        holds.add(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.COMMITTED, NOW.minusMillis(500)));

        Optional<PostSwapResponse> answer =
                replay.awaitInFlightOriginal(OWN_HOLD, waitStoringAt(1, response()));

        assertThat(answer).isPresent();
    }

    // An original committed long ago whose response is not in the cache never stored it (or it has
    // expired). Waiting would only delay the refusal.
    @Test
    void aLongCommittedOriginalWithoutAResponseIsNotWaitedFor() throws CashuErrorException {
        holds.add(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.COMMITTED, NOW.minusSeconds(60)));

        assertThat(replay.awaitInFlightOriginal(OWN_HOLD, waitNeverStoring())).isEmpty();
        assertThat(pausesTaken).isEmpty();
    }

    // The default settle window tolerates seconds of clock skew between replicas: an original the
    // other instance stamped as committed "5 s ago" is still waited for.
    @Test
    void theDefaultSettleWindowToleratesClockSkewBetweenReplicas() throws CashuErrorException {
        holds.add(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.COMMITTED, NOW.minusSeconds(5)));
        InFlightReplayWait skewTolerant = new InFlightReplayWait(pauses(5),
                InFlightReplayWait.DEFAULT.settleWindow(), fixedClock(), millis -> {
                    pausesTaken.add(millis);
                    SwapResponseReplay.of(request, cache, holds).remember(response());
                });

        assertThat(replay.awaitInFlightOriginal(OWN_HOLD, skewTolerant)).isPresent();
    }

    // An original that fails while the replay waits (its hold is released) ends the wait early.
    @Test
    void theWaitEndsWhenTheOriginalFails() throws CashuErrorException {
        SwapHold original = hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.SIGNING, NOW);
        holds.add(original);
        InFlightReplayWait wait = new InFlightReplayWait(pauses(5), Duration.ofSeconds(2),
                fixedClock(), millis -> {
                    pausesTaken.add(millis);
                    holds.replace(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.RELEASED, NOW));
                });

        assertThat(replay.awaitInFlightOriginal(OWN_HOLD, wait)).isEmpty();
        assertThat(pausesTaken).as("one pause, then the released hold ends the wait").hasSize(1);
    }

    // The wait is bounded: an original that never answers leaves the replay refused once the
    // whole budget is spent, not hanging.
    @Test
    void theWaitIsBoundedByItsBudget() throws CashuErrorException {
        holds.add(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.SIGNING, NOW));

        assertThat(replay.awaitInFlightOriginal(OWN_HOLD, waitNeverStoring())).isEmpty();
        assertThat(pausesTaken).hasSize(5);
    }

    // A hold lookup that fails is treated as "nothing in flight", so the refusal stands.
    @Test
    void aFailedHoldLookupLeavesTheRefusalStanding() throws CashuErrorException {
        holds.failure = new IllegalStateException("database unavailable");

        assertThat(replay.awaitInFlightOriginal(OWN_HOLD, waitNeverStoring())).isEmpty();
        assertThat(pausesTaken).isEmpty();
    }

    // An interrupt during a pause ends the wait and keeps the thread's interrupt flag set.
    @Test
    void anInterruptEndsTheWaitAndIsPreserved() throws CashuErrorException {
        holds.add(hold(ORIGINAL_HOLD, fingerprint, SwapHoldPhase.SIGNING, NOW));
        InFlightReplayWait wait = new InFlightReplayWait(pauses(5), Duration.ofSeconds(2),
                fixedClock(), millis -> {
                    throw new InterruptedException();
                });

        try {
            assertThat(replay.awaitInFlightOriginal(OWN_HOLD, wait)).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // --- harness ---------------------------------------------------------------------------

    private InFlightReplayWait waitStoringAt(int pauseNumber, PostSwapResponse original) {
        SwapResponseReplay theOriginal = SwapResponseReplay.of(request, cache, holds);
        return new InFlightReplayWait(pauses(5), Duration.ofSeconds(2), fixedClock(), millis -> {
            pausesTaken.add(millis);
            if (pausesTaken.size() == pauseNumber) {
                theOriginal.remember(original);
            }
        });
    }

    private InFlightReplayWait waitNeverStoring() {
        return new InFlightReplayWait(pauses(5), Duration.ofSeconds(2), fixedClock(), pausesTaken::add);
    }

    private static List<Duration> pauses(int count) {
        List<Duration> pauses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pauses.add(Duration.ofMillis(10));
        }
        return pauses;
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static SwapHold hold(String holdId, SwapRequestFingerprint fingerprint, SwapHoldPhase phase,
                                 Instant updatedAt) {
        return new FakeHold(holdId, fingerprint.hex(), phase, 1, updatedAt, updatedAt);
    }

    private record FakeHold(String holdId, String fingerprintHex, SwapHoldPhase phase, int inputCount,
                            Instant createdAt, Instant updatedAt) implements SwapHold {
    }

    private static final class FakeHolds implements SwapHoldRepository {

        private final List<FakeHold> holds = new ArrayList<>();
        private RuntimeException failure;

        void add(SwapHold hold) {
            holds.add((FakeHold) hold);
        }

        void replace(SwapHold hold) {
            holds.removeIf(existing -> existing.holdId().equals(hold.holdId()));
            add(hold);
        }

        @Override
        public List<SwapHold> findByRequestFingerprint(SwapRequestFingerprint requestFingerprint) {
            if (failure != null) {
                throw failure;
            }
            return holds.stream()
                    .filter(hold -> hold.fingerprintHex().equals(requestFingerprint.hex()))
                    .map(SwapHold.class::cast)
                    .toList();
        }
    }

    private static final class FakeCache implements SwapResponseCache {

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

    private static PostSwapResponse response() {
        return new PostSwapResponse(List.of(new BlindSignature(8, KeysetId.fromString(KEYSET_ID),
                SignatureTestData.sampleSignature(), null)));
    }

    private static PostSwapRequest<RandomStringSecret> request() {
        RSSProof input = new RSSProof();
        input.setAmount(8);
        input.setKeySetId(KEYSET_ID);
        input.setSecret(RandomStringSecret.fromString("aa01"));
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
}
