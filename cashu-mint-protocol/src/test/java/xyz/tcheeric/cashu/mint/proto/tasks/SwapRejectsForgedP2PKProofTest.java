package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

import org.bouncycastle.util.encoders.Hex;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * A P2PK input must be proved to have come from this mint, not merely to be well signed by
 * whoever locked it.
 *
 * <p>The swap path chose a spending condition per secret type and let that condition decide what
 * to check. {@code P2PKSpendingCondition} checks the NUT-11 witness and the locktime; it never
 * looked at {@code C}. So an attacker could mint value from nothing:
 *
 * <ol>
 *   <li>generate a keypair they control, and build a {@code P2PKSecret} locked to it;</li>
 *   <li>set any amount and any active keyset id;</li>
 *   <li>put arbitrary bytes in {@code C}, since nothing verified it;</li>
 *   <li>sign the witness correctly, because they hold the locking key;</li>
 *   <li>post to {@code /v1/swap} with balanced outputs.</li>
 * </ol>
 *
 * <p>The mint would sign the outputs with real keyset keys and commit the forged inputs as spent.
 *
 * <p>These tests use real BDHKE rather than a mocked {@code BDHKEUtils}, so they exercise the
 * actual gate.
 */
@DisplayName("Swap rejects a P2PK proof the mint never issued")
class SwapRejectsForgedP2PKProofTest {

    private static final String KEYSET_ID = "0123456789abcdef";

    /** The mint's keyset key for the amount under test. */
    private static PrivateKey mintKey() {
        return PrivateKey.fromBytes(Schnorr.generatePrivateKey());
    }

    private static MintProtocolService serviceHolding(PrivateKey mintKey) throws CashuErrorException {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class))).thenReturn(mintKey);
        return service;
    }

    /**
     * Builds a P2PK proof whose witness is valid and whose {@code C} is whatever the caller says.
     */
    private static P2PKProof p2pkProof(PrivateKey lockKey, byte[] unblindedSignature) throws Exception {
        P2PKSecret secret = new P2PKSecret(PrivateKey.derivePublicKey(lockKey).getBytes());
        secret.setNSigs(1);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);

        Witness witness = new Witness();
        byte[] message = Utils.sha256(secret.toString().getBytes());
        witness.addSignature(Hex.toHexString(Schnorr.sign(message, lockKey.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setAmount(8);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(secret);
        proof.setWitness(witness);
        proof.setUnblindedSignature(Signature.fromBytes(unblindedSignature));
        return proof;
    }

    /** The unblinded signature the mint would really have produced for this secret. */
    private static byte[] genuineSignature(P2PKSecret secret, PrivateKey mintKey) throws Exception {
        // C = k*Y, where Y = hash_to_curve(secret). Computed directly rather than through a
        // blind/unblind round trip so the test pins the property verify() actually checks.
        org.bouncycastle.math.ec.ECPoint y =
                BDHKEUtils.hashToCurve(secret.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return BDHKEUtils.signBlindedMessage(y, Utils.bigIntFromBytes(mintKey.toBytes()))
                .getEncoded(true);
    }

    private static BlindedMessage output(int amount) {
        BlindedMessage bm = new BlindedMessage();
        bm.setAmount(amount);
        bm.setKeySetId(KeysetId.fromString(KEYSET_ID));
        bm.setBlindedMessage(PublicKey.fromString(
                "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        bm.setWitness(new Witness());
        return bm;
    }

    @SuppressWarnings("unchecked")
    private static PostSwapRequest<P2PKSecret> swapOf(P2PKProof proof, BlindedMessage out) {
        PostSwapRequest<P2PKSecret> request = Mockito.mock(PostSwapRequest.class);
        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(out));
        return request;
    }

    @Test
    @DisplayName("a forged C with a valid witness is rejected")
    void forgedUnblindedSignatureIsRejected() throws Exception {
        PrivateKey mintKey = mintKey();
        PrivateKey attackerLockKey = PrivateKey.fromBytes(Schnorr.generatePrivateKey());

        // Any point at all: the attacker never had a signature from the mint to copy.
        byte[] garbageC = PrivateKey.derivePublicKey(
                PrivateKey.fromBytes(Schnorr.generatePrivateKey())).getBytes();

        P2PKProof forged = p2pkProof(attackerLockKey, garbageC);
        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(
                new Mint(), swapOf(forged, output(8)), serviceHolding(mintKey));

        assertThrows(CashuErrorException.class, task::execute,
                "a P2PK proof the mint never signed must not pass swap verification");
    }

    @Test
    @DisplayName("a genuinely issued P2PK proof still passes")
    void genuinelyIssuedProofStillPasses() throws Exception {
        PrivateKey mintKey = mintKey();
        PrivateKey lockKey = PrivateKey.fromBytes(Schnorr.generatePrivateKey());

        // One proof, then overwrite C with the signature the mint would really have produced for
        // that exact secret. Building a second proof would mint a fresh nonce and a different
        // secret, so the signature would legitimately not match.
        P2PKProof genuine = p2pkProof(lockKey, PrivateKey.derivePublicKey(lockKey).getBytes());
        P2PKSecret secret = (P2PKSecret) genuine.getSecret();
        genuine.setUnblindedSignature(Signature.fromBytes(genuineSignature(secret, mintKey)));

        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(
                new Mint(), swapOf(genuine, output(8)), serviceHolding(mintKey));

        assertDoesNotThrow(task::execute,
                "a proof this mint really issued must still swap");
    }

    @Test
    @DisplayName("a proof against an unknown keyset is rejected rather than skipped")
    void unknownKeysetIsRejected() throws Exception {
        MintProtocolService noKeys = Mockito.mock(MintProtocolService.class);
        Mockito.when(noKeys.getPrivateKey(anyString(), anyInt(), any(Mint.class))).thenReturn(null);

        PrivateKey lockKey = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKProof proof = p2pkProof(lockKey, PrivateKey.derivePublicKey(lockKey).getBytes());

        VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(
                new Mint(), swapOf(proof, output(8)), noKeys);

        assertThrows(CashuErrorException.class, task::execute);
    }
}
