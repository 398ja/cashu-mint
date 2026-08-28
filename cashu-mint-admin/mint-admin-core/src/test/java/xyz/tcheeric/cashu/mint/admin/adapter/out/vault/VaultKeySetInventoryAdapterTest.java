package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort.VaultKeySet;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

/**
 * The two answers a vault can give that must never be confused with each other.
 */
class VaultKeySetInventoryAdapterTest {

    private static final UUID MINT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /**
     * What a running vault actually answers: 404, which the client's plain RestTemplate
     * raises as NotFound. Verified against the dev stack -- GET /vault/keyset/mint/{id}
     * for an unprovisioned mint returns 404 "No KeySetEntity found for the specified mintId".
     */
    @Test
    @DisplayName("A mint the vault answers 404 for is an empty list, not a failure")
    void unprovisionedMintIsEmpty() {
        final var adapter = new VaultKeySetInventoryAdapter(() -> new StubKeySetVaultClient(
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
        final var adapter = new VaultKeySetInventoryAdapter(() -> new StubKeySetVaultClient(
            new IllegalArgumentException("No KeySet found for mintId: " + MINT_ID)));

        final List<VaultKeySet> keySets = adapter.listByMint(MINT_ID);

        assertTrue(keySets.isEmpty());
    }

    /** A vault that cannot be reached must still reach the caller as a failure. */
    @Test
    @DisplayName("A vault that cannot be reached still throws")
    void unreachableVaultThrows() {
        final var adapter = new VaultKeySetInventoryAdapter(() -> new StubKeySetVaultClient(
            new ResourceAccessException("connection refused")));

        assertThrows(ResourceAccessException.class, () -> adapter.listByMint(MINT_ID));
    }

    /** Whatever the vault answers, only the four public fields leave the adapter. */
    @Test
    @DisplayName("A keyset row is described without its key material")
    void describesKeySetWithoutKeys() {
        final KeySetEntity entity = new KeySetEntity();
        entity.setKeySetId("009a1f293253e41e");
        entity.setUnit("sat");
        final var adapter = new VaultKeySetInventoryAdapter(
            () -> new StubKeySetVaultClient(Set.of(entity)));

        final List<VaultKeySet> keySets = adapter.listByMint(MINT_ID);

        assertEquals(1, keySets.size());
        assertEquals("009a1f293253e41e", keySets.get(0).keySetId());
        assertEquals("sat", keySets.get(0).unit());
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
}
