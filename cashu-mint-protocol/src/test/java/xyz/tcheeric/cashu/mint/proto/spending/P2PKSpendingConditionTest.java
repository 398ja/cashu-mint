package xyz.tcheeric.cashu.mint.proto.spending;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.P2PKSecret;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;

import java.util.Collections;

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

    @Test
    public void verifyLocktimeNotReached() throws Exception {
        PrivateKey priv = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        byte[] pub = Schnorr.genPubKey(priv.toBytes());

        P2PKSecret secret = new P2PKSecret(pub);
        secret.setNSigs(1);
        secret.setLockTime(Integer.MAX_VALUE);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);

        Witness witness = new Witness();
        byte[] msg = secret.toString().getBytes();
        witness.addSignature(Hex.toHexString(Schnorr.sign(Utils.sha256(msg), priv.toBytes())));

        P2PKProof proof = new P2PKProof();
        proof.setSecret(secret);
        proof.setWitness(witness);

        P2PKSpendingCondition cond = new P2PKSpendingCondition(Collections.emptyList());
        assertThrows(CashuErrorException.class, () -> cond.verify(proof));
    }
}
