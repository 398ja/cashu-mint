package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort.Denomination;
import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort.VaultKeySet;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.KeyVaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

/**
 * The two answers a vault can give that must never be confused with each other.
 */
class VaultKeySetInventoryAdapterTest {

    private static final UUID MINT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID KEYSET_ROW_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String KEYSET_ID = "009a1f293253e41e";

    /**
     * What a running vault actually answers: 404, which the client's plain RestTemplate
     * raises as NotFound. Verified against the dev stack -- GET /vault/keyset/mint/{id}
     * for an unprovisioned mint returns 404 "No KeySetEntity found for the specified mintId".
     */
    @Test
    @DisplayName("A mint the vault answers 404 for is an empty list, not a failure")
    void unprovisionedMintIsEmpty() {
        final var adapter = adapterFor(new StubKeySetVaultClient(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], null)));

        final List<VaultKeySet> keySets = adapter.listByMint(MINT_ID);

        assertTrue(keySets.isEmpty());
    }

    /**
     * The other way the client says the same thing: a 200 whose body carries no keysets
     * makes it throw IllegalArgumentException rather than answer an empty set.
     */
    @Test
    @DisplayName("A vault answering an empty body is also an empty list")
    void emptyVaultBodyIsEmpty() {
        final var adapter = adapterFor(new StubKeySetVaultClient(
            new IllegalArgumentException("No KeySet found for mintId: " + MINT_ID)));

        final List<VaultKeySet> keySets = adapter.listByMint(MINT_ID);

        assertTrue(keySets.isEmpty());
    }

    /** A vault that cannot be reached must still reach the caller as a failure. */
    @Test
    @DisplayName("A vault that cannot be reached still throws")
    void unreachableVaultThrows() {
        final var adapter = adapterFor(new StubKeySetVaultClient(
            new ResourceAccessException("connection refused")));

        assertThrows(ResourceAccessException.class, () -> adapter.listByMint(MINT_ID));
    }

    /** Whatever the vault answers, only the four public fields leave the adapter. */
    @Test
    @DisplayName("A keyset row is described without its key material")
    void describesKeySetWithoutKeys() {
        final var adapter = adapterFor(new StubKeySetVaultClient(Set.of(keySet())));

        final List<VaultKeySet> keySets = adapter.listByMint(MINT_ID);

        assertEquals(1, keySets.size());
        assertEquals(KEYSET_ID, keySets.get(0).keySetId());
        assertEquals("sat", keySets.get(0).unit());
    }

    /**
     * Denominations come back ascending and carrying the vault path rather than the key:
     * the path is what an Operator backs up, the private key must never leave the vault.
     * Addressed by the keyset id wallets know, resolved to the vault's own row id inside.
     */
    @Test
    @DisplayName("Denominations are listed ascending, as paths rather than keys")
    void listsDenominationsAscendingWithoutPrivateKeys() {
        final var adapter = new VaultKeySetInventoryAdapter(
            () -> new StubKeySetVaultClient(Set.of(keySet())),
            () -> new StubKeyVaultClient(Set.of(key(8), key(2))));

        final List<Denomination> denominations = adapter.listDenominations(MINT_ID, KEYSET_ID);

        assertEquals(List.of(BigInteger.TWO, BigInteger.valueOf(8)),
            denominations.stream().map(Denomination::amount).toList());
        assertEquals("cashu/keys/2", denominations.get(0).vaultPath());
    }

    /** A keyset this mint does not hold is an empty list, never another mint's keys. */
    @Test
    @DisplayName("A keyset the mint does not hold has no denominations")
    void unknownKeySetHasNoDenominations() {
        final var adapter = new VaultKeySetInventoryAdapter(
            () -> new StubKeySetVaultClient(Set.of(keySet())),
            () -> new StubKeyVaultClient(Set.of(key(2))));

        assertTrue(adapter.listDenominations(MINT_ID, "00deadbeefdeadbe").isEmpty());
    }

    private static VaultKeySetInventoryAdapter adapterFor(final KeySetVaultClient keySetClient) {
        return new VaultKeySetInventoryAdapter(() -> keySetClient, StubKeyVaultClient::new);
    }

    private static KeySetEntity keySet() {
        final KeySetEntity entity = new KeySetEntity();
        entity.setId(KEYSET_ROW_ID);
        entity.setKeySetId(KEYSET_ID);
        entity.setUnit("sat");
        return entity;
    }

    private static KeyEntity key(final int amount) {
        final KeyEntity entity = new KeyEntity();
        entity.setAmount(BigInteger.valueOf(amount));
        entity.setVaultPath("cashu/keys/" + amount);
        entity.setPrivateKey("must never leave the vault");
        return entity;
    }

    private static final class StubKeySetVaultClient extends KeySetVaultClient {

        private final Set<KeySetEntity> answer;
        private final RuntimeException failure;

        private StubKeySetVaultClient(final Set<KeySetEntity> answer) {
            this.answer = answer;
            this.failure = null;
        }

        private StubKeySetVaultClient(final RuntimeException failure) {
            this.answer = null;
            this.failure = failure;
        }

        @Override
        public Set<KeySetEntity> getByMintId(final String mintId) {
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private static final class StubKeyVaultClient extends KeyVaultClient {

        private final Set<KeyEntity> answer;

        private StubKeyVaultClient() {
            this(Set.of());
        }

        private StubKeyVaultClient(final Set<KeyEntity> answer) {
            this.answer = answer;
        }

        @Override
        public Set<KeyEntity> getKeysByKeySetId(final String keySetId) {
            assertEquals(KEYSET_ROW_ID.toString(), keySetId,
                "the keys endpoint takes the vault row id, not the keyset id");
            return answer;
        }
    }
}
