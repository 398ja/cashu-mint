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
        return Hex.toHexString(Schnorr.sign(Utils.sha256(secret.toString().getBytes()), priv.toBytes()));
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
