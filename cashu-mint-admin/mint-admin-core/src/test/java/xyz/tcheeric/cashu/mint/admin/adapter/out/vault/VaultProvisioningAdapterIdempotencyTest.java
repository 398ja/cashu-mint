package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.KeyVault;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.VaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

/**
 * Re-provisioning keys that already exist must not write them again (imani-deploy#135 retry).
 *
 * <p>On staging, a provisioning retry for a decommissioned mint re-put each of its archived keys
 * every ~16 minutes. The private key was identical every time, but the HashiCorp backend stores
 * {@code created_at} beside it, so every pass produced a new KV version with a new timestamp. That
 * moved the whole-KV digest the Vault cutover compared, and the cutover was rolled back. A key
 * that is already stored with the same private key is left alone, so its {@code created_at} and
 * KV version stay where they are. A key stored with a DIFFERENT private key is never overwritten:
 * that would silently change what an issued proof is signed with.
 */
class VaultProvisioningAdapterIdempotencyTest {

    private static final UUID MINT_ID = UUID.fromString("5f0a7a8e-2c1d-4b8e-9a41-0d3c6f1e7b22");
    private static final String UNIT = "sat";
    private static final List<Integer> DENOMINATIONS = List.of(1, 2, 4, 8);
    private static final Instant ORIGINAL = Instant.parse("2026-05-01T00:00:00Z");

    private final DeterministicKeyGenerator generator = new DeterministicKeyGenerator();

    @Test
    @DisplayName("Keys already stored with the same private key are not written again")
    void identicalKeysAreNotRewritten() {
        final InMemoryKeyVault keys = new InMemoryKeyVault();
        final String keySetId = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 0);
        final KeySetEntity archived = keySet(keySetId, true);
        seedAll(keys, archived, null);

        adapter(new ArchivedKeySetClient(archived), keys).provision(MINT_ID, UNIT, DENOMINATIONS, 0);

        assertTrue(keys.writes.isEmpty(), "no key may be re-put: " + keys.writes);
        keys.byId.values().forEach(k -> assertEquals(ORIGINAL, k.getCreatedAt()));
    }

    @Test
    @DisplayName("Only the keys that are missing are written")
    void missingKeysAreStillWritten() {
        final InMemoryKeyVault keys = new InMemoryKeyVault();
        final String keySetId = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 0);
        final KeySetEntity keySet = keySet(keySetId, false);
        seedAll(keys, keySet, 8);

        adapter(new ArchivedKeySetClient(keySet), keys).provision(MINT_ID, UNIT, DENOMINATIONS, 0);

        assertEquals(List.of(BigInteger.valueOf(8)), keys.writes);
    }

    @Test
    @DisplayName("A stored key with a different private key is refused, not overwritten")
    void differentStoredKeyIsNotOverwritten() {
        final InMemoryKeyVault keys = new InMemoryKeyVault();
        final String keySetId = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 0);
        final KeySetEntity keySet = keySet(keySetId, false);
        seedAll(keys, keySet, null);
        final KeyEntity one = keys.byId.get(generator.deterministicId(MINT_ID, UNIT, "1").toString());
        one.setPrivateKey("ff".repeat(32));

        assertThrows(IllegalStateException.class, () ->
            adapter(new ArchivedKeySetClient(keySet), keys).provision(MINT_ID, UNIT, DENOMINATIONS, 0));
        assertTrue(keys.writes.isEmpty(), "nothing may be overwritten: " + keys.writes);
        assertEquals("ff".repeat(32), one.getPrivateKey());
    }

    private void seedAll(final InMemoryKeyVault keys, final KeySetEntity keySet, final Integer skip) {
        for (final int amount : DENOMINATIONS) {
            if (skip != null && skip == amount) {
                continue;
            }
            final KeyEntity key = new KeyEntity();
            key.setId(generator.deterministicId(MINT_ID, UNIT, String.valueOf(amount)));
            key.setAmount(BigInteger.valueOf(amount));
            key.setPrivateKey(generator.derivePrivateKeyHex(MINT_ID, UNIT, amount, null));
            key.setKeySet(keySet);
            key.setCreatedAt(ORIGINAL);
            keys.byId.put(key.getId().toString(), key);
        }
    }

    private static KeySetEntity keySet(final String keySetId, final boolean archived) {
        final KeySetEntity keySet = new KeySetEntity();
        keySet.setId(UUID.nameUUIDFromBytes(keySetId.getBytes()));
        keySet.setKeySetId(keySetId);
        keySet.setUnit(UNIT);
        keySet.setArchived(archived);
        return keySet;
    }

    private VaultProvisioningAdapter adapter(final KeySetVaultClient keySets, final KeyVault keys) {
        return new VaultProvisioningAdapter(generator, () -> keySets, () -> keys, StubMintVaultClient::new);
    }

    /** The mint's one keyset; a second store of it is the vault's 409. */
    private static final class ArchivedKeySetClient extends KeySetVaultClient {
        private final KeySetEntity keySet;

        private ArchivedKeySetClient(final KeySetEntity keySet) {
            this.keySet = keySet;
        }

        @Override
        public Set<KeySetEntity> getByMintId(final String mintId) {
            return Set.of(keySet);
        }

        @Override
        public KeySetEntity store(final KeySetEntity entity) {
            throw HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                new byte[0], null);
        }

        @Override
        public KeySetEntity getByKeySetId(final String keySetId) {
            return keySet;
        }
    }

    /**
     * Behaves like the HashiCorp-backed key vault: {@code store} always writes (a new KV version,
     * a new created_at), {@code retrieve} answers the stored key with its private key, and a key
     * that does not exist is "Key not found".
     */
    private static final class InMemoryKeyVault implements KeyVault {
        final Map<String, KeyEntity> byId = new ConcurrentHashMap<>();
        final List<BigInteger> writes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public KeyEntity store(final KeyEntity entity) {
            writes.add(entity.getAmount());
            entity.setCreatedAt(Instant.now());
            byId.put(entity.getId().toString(), entity);
            return entity;
        }

        @Override
        public KeyEntity retrieve(final String id) throws CashuErrorException {
            final KeyEntity key = byId.get(id);
            if (key == null) {
                throw new CashuErrorException("Key not found");
            }
            return key;
        }

        @Override
        public KeyEntity retrieveByAmount(final BigInteger amount, final String keySetId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KeyEntity archive(final String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(final String id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubMintVaultClient extends VaultClient<MintEntity> {
        private StubMintVaultClient() {
            super(MintEntity.class, "http://vault.invalid");
        }

        @Override
        public MintEntity store(final MintEntity entity) {
            return entity;
        }
    }
}
