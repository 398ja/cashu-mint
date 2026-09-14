package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import org.bouncycastle.util.encoders.Hex;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The NUT-11 threshold must count DISTINCT public keys, not signatures. If it counted
 * signatures, one key could satisfy a 2-of-2 by signing twice, which is a forged multisig.
 */
class SigCountTest {

    // Confirms one key submitting its signature twice counts once, not twice.
    @Test
    void oneKeySigningTwiceCannotSatisfyATwoOfTwo() throws Exception {
        byte[] sk = Schnorr.generatePrivateKey();
        byte[] pk = Schnorr.genPubKey(sk);
        byte[] message = "spend-me".getBytes();
        byte[] hash = Utils.sha256(message);
        String sig = Hex.toHexString(Schnorr.sign(hash, sk));
        String key = Hex.toHexString(pk);

        int counted = SigningKeyCounter.countSigningKeys(
                List.of(key, key), List.of(sig, sig), message);

        assertEquals(1, counted, "duplicate key must count once");
    }

    // Confirms two genuinely distinct keys do both count, so the rule is not just 'always 1'.
    @Test
    void twoDistinctKeysBothCount() throws Exception {
        byte[] skA = Schnorr.generatePrivateKey();
        byte[] skB = Schnorr.generatePrivateKey();
        byte[] message = "spend-me".getBytes();
        byte[] hash = Utils.sha256(message);

        int counted = SigningKeyCounter.countSigningKeys(
                List.of(Hex.toHexString(Schnorr.genPubKey(skA)),
                        Hex.toHexString(Schnorr.genPubKey(skB))),
                List.of(Hex.toHexString(Schnorr.sign(hash, skA)),
                        Hex.toHexString(Schnorr.sign(hash, skB))),
                message);

        assertEquals(2, counted);
    }
}
