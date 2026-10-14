package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Test-only seeds: a fresh CSPRNG seed per call, so no test depends on a committed secret. */
public final class TestSeeds {

    private TestSeeds() {
    }

    public static KeysetMasterSeed random() {
        final byte[] bytes = new byte[KeysetMasterSeed.LENGTH];
        new SecureRandom().nextBytes(bytes);
        return KeysetMasterSeed.fromHex(HexFormat.of().formatHex(bytes));
    }

    public static DeterministicKeyGenerator generator() {
        final KeysetMasterSeed seed = random();
        return new DeterministicKeyGenerator(() -> seed);
    }
}
