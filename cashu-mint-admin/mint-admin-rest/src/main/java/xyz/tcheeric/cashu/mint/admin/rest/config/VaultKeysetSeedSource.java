package xyz.tcheeric.cashu.mint.admin.rest.config;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultVersionedKeyValueOperations;
import org.springframework.vault.support.Versioned;

import xyz.tcheeric.cashu.mint.admin.adapter.out.vault.KeysetMasterSeed;

/**
 * Reads the keyset master seed from HashiCorp Vault KV v2 (cashu-mint#545).
 *
 * <p>The seed lives at {@code <mount>/<path>} under the field {@code seed}. It is read once, at
 * startup, validated by {@link KeysetMasterSeed#fromHex}, and held in memory. It is never logged:
 * only its {@link KeysetMasterSeed#fingerprint()} is.
 *
 * <p>There is no fallback. A missing, unreadable, malformed or weak seed fails startup, because the
 * alternative, deriving keys without a secret, is exactly the defect being fixed.
 *
 * <p>{@code generateIfAbsent} exists for disposable stacks (the e2e compose stack, a local gate),
 * whose dev-mode Vault starts empty. It writes 32 CSPRNG bytes with check-and-set version 0, which
 * Vault accepts only when the path has never been written, so it can create a seed but can never
 * replace one: replacing the seed would change the keys of every keyset still being provisioned or
 * rotated. It is off by default and must stay off for staging and production, where the seed is
 * created deliberately by an operator (see the keyset rotation runbook).
 */
public final class VaultKeysetSeedSource implements Supplier<KeysetMasterSeed> {

    private static final Logger log = LoggerFactory.getLogger(VaultKeysetSeedSource.class);

    static final String FIELD = "seed";

    private final KeysetMasterSeed seed;

    VaultKeysetSeedSource(final VaultTemplate vaultTemplate, final String mount, final String path,
                          final boolean generateIfAbsent) {
        Objects.requireNonNull(vaultTemplate, "vault template must not be null");
        final VaultVersionedKeyValueOperations kv = vaultTemplate.opsForVersionedKeyValue(mount);
        this.seed = load(kv, mount, path, generateIfAbsent);
        log.info("Keyset master seed loaded from Vault {}/{} (fingerprint {})", mount, path, seed.fingerprint());
    }

    private static KeysetMasterSeed load(final VaultVersionedKeyValueOperations kv, final String mount,
                                         final String path, final boolean generateIfAbsent) {
        String hex = read(kv, mount, path);
        if (hex == null && generateIfAbsent) {
            create(kv, mount, path);
            hex = read(kv, mount, path);
        }
        if (hex == null) {
            throw new IllegalStateException("No keyset master seed in Vault at " + mount + "/" + path
                + " (field '" + FIELD + "'). mint-admin refuses to provision keysets without one: "
                + "keys derived without a secret can be recomputed from the mint id (cashu-mint#545). "
                + "Create it once with: vault kv put -cas=0 " + mount + "/" + path + " " + FIELD
                + "=\"$(openssl rand -hex 32)\"");
        }
        try {
            return KeysetMasterSeed.fromHex(hex);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("The keyset master seed in Vault at " + mount + "/" + path
                + " is not usable: " + e.getMessage(), e);
        }
    }

    private static String read(final VaultVersionedKeyValueOperations kv, final String mount, final String path) {
        final Versioned<Map<String, Object>> secret;
        try {
            secret = kv.get(path);
        } catch (final RuntimeException e) {
            throw new IllegalStateException("Could not read the keyset master seed from Vault at "
                + mount + "/" + path + ": " + e.getClass().getSimpleName(), e);
        }
        if (secret == null || !secret.hasData() || secret.getData() == null) {
            return null;
        }
        final Object value = secret.getData().get(FIELD);
        return value == null ? null : value.toString();
    }

    private static void create(final VaultVersionedKeyValueOperations kv, final String mount, final String path) {
        final byte[] bytes = new byte[KeysetMasterSeed.LENGTH];
        new SecureRandom().nextBytes(bytes);
        try {
            kv.put(path, Versioned.create(Map.of(FIELD, HexFormat.of().formatHex(bytes)),
                Versioned.Version.unversioned()));
            log.warn("Generated a NEW keyset master seed at Vault {}/{} because "
                + "admin.keyset-seed.generate-if-absent=true. This is for disposable stacks only.", mount, path);
        } catch (final RuntimeException e) {
            // A concurrent writer won the CAS, which is fine: the re-read takes its seed.
            log.info("Keyset master seed was created concurrently at Vault {}/{}; using it", mount, path);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public KeysetMasterSeed get() {
        return seed;
    }
}
