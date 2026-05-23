package xyz.tcheeric.cashu.mint.rest.spec002.support;

import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Spec 002 mint-path IT fixture. Builds a synthetic keyset whose mint
 * private keys are well-known small hex values (1, 2, 4, ... 128), so
 * tests can construct {@code Proof} JSON payloads whose
 * unblinded-signature bytes verify against the keyset.
 *
 * <p>Verification math (BDHKE direct path, no blinding factor):
 * <ul>
 *   <li>{@code Y = hashToCurve(secret)}</li>
 *   <li>{@code C = priv * Y} — computed by
 *       {@link BDHKEUtils#signBlindedMessage(byte[], byte[])}</li>
 *   <li>The mint's {@code BDHKEUtils.verify(secret, priv, C)} re-derives
 *       {@code Y' = hashToCurve(secret)} and asserts {@code priv * Y' == C}.</li>
 * </ul>
 *
 * <p>The fixture sidesteps the BDHKE blinding protocol since wallet
 * blinding cancels at the unblind step ({@code C = C_ - r*K_pub = priv*Y}).
 * Skipping the round trip is sound for mint-side verification tests.
 */
public final class MeltProofFixture {

    public static final String KEYSET_ID = "004cf8cba2f93266";
    public static final UUID MINT_UUID = UUID.fromString("00000002-0000-0000-0000-000000000002");

    private MeltProofFixture() {
    }

    /**
     * Construct a Mint whose only keyset is the IT keyset with private keys
     * tied to the powers-of-two amount → secret hex pattern.
     */
    public static Mint mintWithKeys() {
        Mint mint = new Mint(MINT_UUID.toString());
        Keys keys = new Keys();
        for (int amount : new int[]{1, 2, 4, 8, 16, 32, 64, 128}) {
            PrivateKey priv = privateKeyFor(amount);
            keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(priv));
        }
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }

    /** Deterministic mint private key per amount, matching {@link #mintWithKeys()}. */
    public static PrivateKey privateKeyFor(int amount) {
        return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
    }

    /**
     * Build a verifying proof for a given amount + secret. The {@code C}
     * bytes are computed as {@code priv * hashToCurve(secret)} so the
     * mint's {@code BDHKEUtils.verify} returns true.
     */
    public static Map<String, Object> proofJson(int amount, String secret) {
        byte[] yBytes = BDHKEUtils.hashToCurve(secret);
        byte[] cBytes = BDHKEUtils.signBlindedMessage(yBytes, privateKeyFor(amount).toBytes());
        return Map.of(
                "amount", amount,
                "id", KEYSET_ID,
                "secret", secret,
                "C", HexFormat.of().formatHex(cBytes));
    }

    /** Convenience: build a proof with a randomised 64-hex secret (NUT-00 random-secret form). */
    public static Map<String, Object> proofJson(int amount) {
        return proofJson(amount, randomHexSecret());
    }

    /** 64-char hex (32 random bytes), matching the NUT-00 random-secret convention. */
    public static String randomHexSecret() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        byte[] bytes = new byte[32];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * NUT-08 helper: build a {@code BlindedMessage} the mint can sign for
     * the change-return path. The wallet would normally derive {@code B_}
     * from a fresh secret + blinding factor; for the IT we just need a
     * valid secp256k1 point, so we use {@code derivePublicKey(priv*N)} for
     * a deterministic small private key (N picked per-call so the points
     * differ).
     */
    public static xyz.tcheeric.cashu.common.BlindedMessage blindedMessageForChange(int amount, int salt) {
        java.math.BigInteger scalar = BigInteger.valueOf(amount).add(BigInteger.valueOf(salt))
                .add(BigInteger.valueOf(257)); // avoid colliding with mint priv keys
        String hex = String.format("%064x", scalar);
        PrivateKey priv = PrivateKey.fromString(hex);
        xyz.tcheeric.cashu.common.PublicKey pub = PrivateKey.derivePublicKey(priv);
        return xyz.tcheeric.cashu.common.BlindedMessage.builder()
                .amount(amount)
                .keySetId(xyz.tcheeric.cashu.common.KeysetId.fromString(KEYSET_ID))
                .blindedMessage(pub)
                .build();
    }
}
