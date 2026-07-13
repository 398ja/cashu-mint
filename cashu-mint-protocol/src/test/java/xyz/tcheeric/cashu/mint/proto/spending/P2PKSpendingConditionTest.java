package xyz.tcheeric.cashu.mint.proto.spending;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class P2PKSpendingConditionTest {

    @Test
    public void verifyValidProof() throws Exception {
        PrivateKey priv = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        byte[] pub = Schnorr.genPubKey(priv.toBytes());

        P2PKSecret secret = new P2PKSecret(pub);
        secret.setNSigs(1);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);

        Witness witness = new Witness();
        byte[] msg = secret.toString().getBytes();
        witness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(msg), priv.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        P2PKSpendingCondition cond = new P2PKSpendingCondition(Collections.emptyList());
        assertDoesNotThrow(() -> cond.verify(proof));
    }

    @Test
    public void verifyInvalidSignature() throws Exception {
        PrivateKey priv = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        byte[] pub = Schnorr.genPubKey(priv.toBytes());

        P2PKSecret secret = new P2PKSecret(pub);
        secret.setNSigs(1);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);

        Witness witness = new Witness();
        witness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256("wrong".getBytes()), priv.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        P2PKSpendingCondition cond = new P2PKSpendingCondition(Collections.emptyList());
        assertThrows(CashuErrorException.class, () -> cond.verify(proof));
    }

    /**
     * NUT-11: the primary key is spendable at ANY time, including before a future locktime — the
     * locktime only gates the refund path. (Corrects the prior expectation, which rejected a valid
     * primary spend before locktime — the inverted-locktime bug the escrow work fixes.)
     */
    @Test
    public void verifyPrimaryKeySpendableBeforeLocktime() throws Exception {
        PrivateKey priv = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        byte[] pub = Schnorr.genPubKey(priv.toBytes());

        P2PKSecret secret = new P2PKSecret(pub);
        secret.setNSigs(1);
        secret.setLockTime(Integer.MAX_VALUE); // far future — not reached
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);

        Witness witness = new Witness();
        byte[] msg = secret.toString().getBytes();
        witness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(msg), priv.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        P2PKSpendingCondition cond = new P2PKSpendingCondition(Collections.emptyList());
        assertDoesNotThrow(() -> cond.verify(proof));
    }

    // ----- Dalia Phase 9: 2-of-3 escrow + locktime-refund semantics -----

    private static String signSecret(P2PKSecret secret, PrivateKey priv) throws Exception {
        return Hex.toHexString(Schnorr.sign(
                Utils.sha256(secret.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), priv.toBytes()));
    }

    /** Builds a 2-of-3 secret with data=A, pubkeys tag=[B,L], optional refund + locktime. */
    private static P2PKSecret twoOfThree(byte[] pubA, byte[] pubB, byte[] pubL, Integer lockTime, byte[] refund) {
        P2PKSecret secret = new P2PKSecret(pubA);
        secret.setNSigs(2);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        secret.setPubKeys(List.of(Hex.toHexString(pubB), Hex.toHexString(pubL)));
        if (lockTime != null) {
            secret.setLockTime(lockTime);
        }
        if (refund != null) {
            secret.setRefund(List.of(Hex.toHexString(refund)));
        }
        return secret;
    }

    /** 2-of-3: two valid signatures release the escrow (before/without locktime). */
    @Test
    public void twoOfThree_twoSignatures_release() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey b = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey l = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKSecret secret = twoOfThree(Schnorr.genPubKey(a.toBytes()), Schnorr.genPubKey(b.toBytes()),
                Schnorr.genPubKey(l.toBytes()), null, null);

        Witness witness = new Witness();
        witness.addSignature(signSecret(secret, a));
        witness.addSignature(signSecret(secret, b));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        assertDoesNotThrow(() -> new P2PKSpendingCondition(Collections.emptyList()).verify(proof));
    }

    /** 2-of-3: a single signature does NOT release the escrow (no unilateral sweep). */
    @Test
    public void twoOfThree_oneSignature_rejected() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey b = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey l = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKSecret secret = twoOfThree(Schnorr.genPubKey(a.toBytes()), Schnorr.genPubKey(b.toBytes()),
                Schnorr.genPubKey(l.toBytes()), null, null);

        Witness witness = new Witness();
        witness.addSignature(signSecret(secret, a)); // only one of the three

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(Collections.emptyList()).verify(proof));
    }

    /** SIG_ALL with no outputs is rejected as a protocol error (CashuErrorException), not IllegalStateException/500. */
    @Test
    public void sigAll_missingOutputs_protocolError() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKSecret secret = new P2PKSecret(Schnorr.genPubKey(a.toBytes()));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_ALL);

        Witness witness = new Witness();
        witness.addSignature(signSecret(secret, a));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        // No blinded messages supplied under SIG_ALL → protocol error, not a 500.
        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(Collections.emptyList()).verify(proof));
    }

    /** Refund: after the locktime, the refund key alone reclaims the escrow. */
    @Test
    public void refundKey_afterLocktime_reclaims() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey b = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey l = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey refund = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        // Locktime in the past; refund key is distinct from the primary set.
        P2PKSecret secret = twoOfThree(Schnorr.genPubKey(a.toBytes()), Schnorr.genPubKey(b.toBytes()),
                Schnorr.genPubKey(l.toBytes()), 1000, Schnorr.genPubKey(refund.toBytes()));

        Witness witness = new Witness();
        witness.addSignature(signSecret(secret, refund));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        assertDoesNotThrow(() -> new P2PKSpendingCondition(Collections.emptyList()).verify(proof));
    }

    /**
     * NUT-11: a single key's signature duplicated must NOT satisfy a 2-of-3 — the threshold counts
     * distinct public keys, not raw signatures. Guards against unilateral escrow theft.
     */
    @Test
    public void twoOfThree_duplicateSignatureFromOneKey_rejected() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey b = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey l = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKSecret secret = twoOfThree(Schnorr.genPubKey(a.toBytes()), Schnorr.genPubKey(b.toBytes()),
                Schnorr.genPubKey(l.toBytes()), null, null);

        String sigB = signSecret(secret, b);
        Witness witness = new Witness();
        witness.addSignature(sigB);
        witness.addSignature(sigB); // the SAME signature twice — must not count as two

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(Collections.emptyList()).verify(proof));
    }

    /**
     * NUT-11 {@code n_sigs_refund}: a 2-of-3 refund key set requires TWO valid refund signatures,
     * not just one — the refund path now honors the same threshold semantics as the primary path.
     */
    @Test
    public void refundThreshold_twoOf_requiresTwoRefundSigs() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey b = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey l = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey r1 = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey r2 = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey r3 = PrivateKey.fromBytes(Schnorr.generatePrivateKey());

        P2PKSecret secret = twoOfThree(Schnorr.genPubKey(a.toBytes()), Schnorr.genPubKey(b.toBytes()),
                Schnorr.genPubKey(l.toBytes()), 1000, null); // locktime in the past
        secret.setRefund(List.of(
                Hex.toHexString(Schnorr.genPubKey(r1.toBytes())),
                Hex.toHexString(Schnorr.genPubKey(r2.toBytes())),
                Hex.toHexString(Schnorr.genPubKey(r3.toBytes()))));
        secret.setNSigsRefund(2);

        // One valid refund signature is not enough for a 2-of-3 refund threshold.
        Witness oneSig = new Witness();
        oneSig.addSignature(signSecret(secret, r1));
        P2PKProof oneSigProof = new P2PKProof();
        oneSigProof.setSecret(secret);
        oneSigProof.setWitness(oneSig);
        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(Collections.emptyList()).verify(oneSigProof));

        // Two valid refund signatures satisfy the threshold.
        Witness twoSigs = new Witness();
        twoSigs.addSignature(signSecret(secret, r1));
        twoSigs.addSignature(signSecret(secret, r2));
        P2PKProof twoSigsProof = new P2PKProof();
        twoSigsProof.setSecret(secret);
        twoSigsProof.setWitness(twoSigs);
        assertDoesNotThrow(() -> new P2PKSpendingCondition(Collections.emptyList()).verify(twoSigsProof));
    }

    /**
     * Backward compat: an escrow with NO {@code n_sigs_refund} set (default threshold 1) still
     * reclaims with a single refund signature — existing escrows are unaffected by the change.
     */
    @Test
    public void refundDefault_oneSigStillReclaims() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey b = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey l = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey refund = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        // Locktime in the past; refund key is distinct from the primary set; n_sigs_refund unset.
        P2PKSecret secret = twoOfThree(Schnorr.genPubKey(a.toBytes()), Schnorr.genPubKey(b.toBytes()),
                Schnorr.genPubKey(l.toBytes()), 1000, Schnorr.genPubKey(refund.toBytes()));

        Witness witness = new Witness();
        witness.addSignature(signSecret(secret, refund));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        assertDoesNotThrow(() -> new P2PKSpendingCondition(Collections.emptyList()).verify(proof));
    }

    /** Refund: BEFORE the locktime, the refund key cannot reclaim (no early refund). */
    @Test
    public void refundKey_beforeLocktime_rejected() throws Exception {
        PrivateKey a = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey b = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey l = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey refund = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        P2PKSecret secret = twoOfThree(Schnorr.genPubKey(a.toBytes()), Schnorr.genPubKey(b.toBytes()),
                Schnorr.genPubKey(l.toBytes()), Integer.MAX_VALUE, Schnorr.genPubKey(refund.toBytes()));

        Witness witness = new Witness();
        witness.addSignature(signSecret(secret, refund)); // refund alone, before locktime

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(Collections.emptyList()).verify(proof));
    }
}
