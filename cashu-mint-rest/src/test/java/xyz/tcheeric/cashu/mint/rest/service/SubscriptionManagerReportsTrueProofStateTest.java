package xyz.tcheeric.cashu.mint.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.common.nut17.JsonRpcNotification;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code proof_state} subscription must report the state the vault actually holds.
 *
 * <p>NUT-17 delivers the hash-to-curve point Y on the wire, exactly as NUT-07 does. The vault has
 * two lookups that both take a {@code String}: {@code retrieveProof(mintId, secret)} hashes its
 * input to derive the storage key, and {@code retrieveProofByY(y)} does not. Handing an
 * already-hashed Y to the hashing form hashes it a second time, so the lookup misses every row.
 * {@code SubscriptionManager.fetchProofState} maps a miss to {@code UNSPENT}, so the subscriber is
 * told its SPENT and PENDING proofs are unspent, with no exception and no log. See #485.
 *
 * <p>These tests deliberately do not verify which vault method was called. The predecessor test did
 * exactly that and passed while the bug was live, because "the code calls this method" moves
 * whenever the code moves. Here the vault is a real map keyed on the true Y, and the hashing lookup
 * genuinely applies {@link SecretUtil#toYFromString(String)}, so a double hash misses the map and
 * the assertion fails on the reported <em>state</em>.
 */
@DisplayName("a proof_state subscription reports the proof's true state")
class SubscriptionManagerReportsTrueProofStateTest {

    /**
     * Y values as a wallet computes them: hash_to_curve over the secret. A subscription carries
     * these, never the secret.
     */
    private static final String Y_OF_SPENT_PROOF =
            SecretUtil.toYFromString("a5c0e0a5e9e3d2c1b0a9f8e7d6c5b4a3928170615043f2e1d0c9b8a796857463");
    private static final String Y_OF_PENDING_PROOF =
            SecretUtil.toYFromString("bb11e0a5e9e3d2c1b0a9f8e7d6c5b4a3928170615043f2e1d0c9b8a796857463");
    private static final String Y_OF_PROOF_THE_MINT_NEVER_SAW =
            SecretUtil.toYFromString("cc22e0a5e9e3d2c1b0a9f8e7d6c5b4a3928170615043f2e1d0c9b8a796857463");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VaultKeyedOnTheTrueY vault = new VaultKeyedOnTheTrueY();

    private SubscriptionManager subscriptionManager;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        session = Mockito.mock(WebSocketSession.class);
        Mockito.lenient().when(session.getId()).thenReturn("session-1");
        Mockito.lenient().when(session.isOpen()).thenReturn(true);

        subscriptionManager = new SubscriptionManager(
                objectMapper, vault, Mockito.mock(MintProtocolService.class), "sat");
    }

    /**
     * The premise the rest of this class rests on: hashing a Y a second time yields a different
     * value, so the hashing lookup cannot find a row stored under the true Y. If this ever flips,
     * the whole bug class disappears and these tests can go with it.
     */
    @Test
    @DisplayName("the fake vault misses when an already-hashed Y reaches the hashing lookup")
    void theFakeVaultMissesWhenAnAlreadyHashedYReachesTheHashingLookup() throws Exception {
        vault.record(Y_OF_SPENT_PROOF, ProofEntity.STATE_SPENT, null);

        assertThat(vault.retrieveProofByY(Y_OF_SPENT_PROOF))
                .as("the Y-keyed lookup finds the row it was stored under")
                .isNotNull();
        assertThat(vault.retrieveProof(UUID.randomUUID(), Y_OF_SPENT_PROOF))
                .as("the hashing lookup hashes the Y again and misses, which is bug #485 in miniature")
                .isNull();
    }

    /** A proof the vault holds as SPENT must be reported SPENT, not UNSPENT. */
    @Test
    @DisplayName("a spent proof is reported SPENT")
    void aSpentProofIsReportedSpent() throws Exception {
        vault.record(Y_OF_SPENT_PROOF, ProofEntity.STATE_SPENT, "spent-witness");

        Map<String, ProofStateNotification> reported = statesReportedFor(Y_OF_SPENT_PROOF);

        assertThat(reported.get(Y_OF_SPENT_PROOF).state())
                .as("a subscriber watching a spent proof must be told it is spent")
                .isEqualTo("SPENT");
        assertThat(reported.get(Y_OF_SPENT_PROOF).witness()).isEqualTo("spent-witness");
    }

    /** A proof the vault holds as PENDING must be reported PENDING, not UNSPENT and not SPENT. */
    @Test
    @DisplayName("a pending proof is reported PENDING")
    void aPendingProofIsReportedPending() throws Exception {
        vault.record(Y_OF_PENDING_PROOF, ProofEntity.STATE_PENDING, "pending-witness");

        Map<String, ProofStateNotification> reported = statesReportedFor(Y_OF_PENDING_PROOF);

        assertThat(reported.get(Y_OF_PENDING_PROOF).state())
                .as("PENDING is not terminal and must not be flattened into SPENT or UNSPENT")
                .isEqualTo("PENDING");
        assertThat(reported.get(Y_OF_PENDING_PROOF).witness()).isEqualTo("pending-witness");
    }

    /**
     * The other direction: UNSPENT must mean the vault genuinely holds no row, so that the
     * spent-proof assertions above cannot be satisfied by a lookup that always misses.
     */
    @Test
    @DisplayName("a proof the mint never saw is reported UNSPENT")
    void aProofTheMintNeverSawIsReportedUnspent() throws Exception {
        Map<String, ProofStateNotification> reported = statesReportedFor(Y_OF_PROOF_THE_MINT_NEVER_SAW);

        assertThat(reported.get(Y_OF_PROOF_THE_MINT_NEVER_SAW).state())
                .as("an absent proof is unspent")
                .isEqualTo("UNSPENT");
        assertThat(reported.get(Y_OF_PROOF_THE_MINT_NEVER_SAW).witness()).isNull();
    }

    /**
     * The assertion with the sharpest teeth: one subscription over three proofs must produce three
     * different answers. A lookup that double-hashes collapses all three to UNSPENT, which no
     * per-proof stub can disguise, and the failure names the state rather than the method call.
     */
    @Test
    @DisplayName("one subscription distinguishes spent, pending and absent proofs")
    void oneSubscriptionDistinguishesSpentPendingAndAbsentProofs() throws Exception {
        vault.record(Y_OF_SPENT_PROOF, ProofEntity.STATE_SPENT, null);
        vault.record(Y_OF_PENDING_PROOF, ProofEntity.STATE_PENDING, null);

        Map<String, ProofStateNotification> reported = statesReportedFor(
                Y_OF_SPENT_PROOF, Y_OF_PENDING_PROOF, Y_OF_PROOF_THE_MINT_NEVER_SAW);

        assertThat(reported.keySet())
                .as("every subscribed Y gets its own notification")
                .containsExactlyInAnyOrder(
                        Y_OF_SPENT_PROOF, Y_OF_PENDING_PROOF, Y_OF_PROOF_THE_MINT_NEVER_SAW);
        assertThat(Map.of(
                Y_OF_SPENT_PROOF, reported.get(Y_OF_SPENT_PROOF).state(),
                Y_OF_PENDING_PROOF, reported.get(Y_OF_PENDING_PROOF).state(),
                Y_OF_PROOF_THE_MINT_NEVER_SAW, reported.get(Y_OF_PROOF_THE_MINT_NEVER_SAW).state()))
                .as("a double-hashed lookup reports all three as UNSPENT, which is #485")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        Y_OF_SPENT_PROOF, "SPENT",
                        Y_OF_PENDING_PROOF, "PENDING",
                        Y_OF_PROOF_THE_MINT_NEVER_SAW, "UNSPENT"));
    }

    /**
     * Subscribes to the given Y values, asks for the current state, and returns what actually went
     * down the socket, indexed by the Y each notification names.
     */
    private Map<String, ProofStateNotification> statesReportedFor(String... yValues) throws IOException {
        String subId = subscriptionManager.subscribe(session, SubscriptionKind.proof_state, List.of(yValues));
        subscriptionManager.sendCurrentState(session, subId, SubscriptionKind.proof_state);

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        Mockito.verify(session, Mockito.atLeast(0)).sendMessage(messages.capture());

        Map<String, ProofStateNotification> byY = new LinkedHashMap<>();
        for (TextMessage message : messages.getAllValues()) {
            JsonRpcNotification notification =
                    objectMapper.readValue(message.getPayload(), JsonRpcNotification.class);
            @SuppressWarnings("unchecked") // NUT-17 proof_state payloads are {Y, state, witness?}
            Map<String, String> payload = (Map<String, String>) notification.getParams().getPayload();
            byY.put(payload.get("Y"),
                    new ProofStateNotification(payload.get("state"), payload.get("witness")));
        }
        return byY;
    }

    /** What a subscriber actually received for one Y. */
    private record ProofStateNotification(String state, String witness) {}

    /**
     * A vault keyed on the true storage key Y, with the two lookups behaving as production does:
     * {@code retrieveProof} hashes its input with the real hash_to_curve, {@code retrieveProofByY}
     * does not. This is what makes a double hash observable as a wrong state rather than as an
     * unexpected method call.
     */
    private static final class VaultKeyedOnTheTrueY implements ProofVaultService {

        private final Map<String, ProofEntity> proofsByY = new HashMap<>();

        void record(String y, String state, String witness) {
            ProofEntity proof = new ProofEntity();
            proof.setSecret(y);
            proof.setState(state);
            proof.setWitness(witness);
            proofsByY.put(y, proof);
        }

        @Override
        public ProofEntity retrieveProofByY(String yHex) {
            return proofsByY.get(yHex);
        }

        @Override
        public ProofEntity retrieveProof(UUID mintId, String secret) {
            return proofsByY.get(SecretUtil.toYFromString(secret));
        }

        @Override
        public void store(ProofEntity proofEntity) {
            throw new UnsupportedOperationException("a subscription only reads");
        }

        @Override
        public void invalidate(ProofEntity proofEntity) {
            throw new UnsupportedOperationException("a subscription only reads");
        }

        @Override
        public void archive(ProofEntity proofEntity) {
            throw new UnsupportedOperationException("a subscription only reads");
        }

        @Override
        public void storePending(ProofEntity proofEntity) {
            throw new UnsupportedOperationException("a subscription only reads");
        }
    }
}
