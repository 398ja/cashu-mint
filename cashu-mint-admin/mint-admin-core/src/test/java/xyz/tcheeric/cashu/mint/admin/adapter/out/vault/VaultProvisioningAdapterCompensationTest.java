package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.VaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

/**
 * What compensating a failed provisioning may undo (cashu-mint#484): the mint row while it
 * owns nothing, and never a mint that owns a keyset.
 */
class VaultProvisioningAdapterCompensationTest {

    private static final UUID MINT_ID = UUID.fromString("cac80039-81fa-473d-a398-13de39a4aa8a");

    // The staging case: provisioning was retried after it had already created the mint's
    // keyset. The vault's foreign key refuses the delete, and attempting it anyway filled the
    // vault log with ERROR lines describing an integrity failure that was not one. A mint that
    // owns a keyset must not be deleted at all.
    @Test
    @DisplayName("A mint that owns a keyset is not deleted")
    void mintOwningAKeySetIsNotDeleted() {
        final RecordingMintVaultClient mints = new RecordingMintVaultClient();

        adapter(new StubKeySetVaultClient(Set.of(keySet("01ef5b83"))), mints).compensate(MINT_ID);

        assertTrue(mints.deleted.isEmpty());
    }

    // A mint whose provisioning failed before any keyset was written has only its row to undo,
    // and compensation removes it as before.
    @Test
    @DisplayName("A mint with no keyset is rolled back")
    void mintWithNoKeySetIsDeleted() {
        final RecordingMintVaultClient mints = new RecordingMintVaultClient();

        adapter(StubKeySetVaultClient.notFound(), mints).compensate(MINT_ID);

        assertEquals(List.of(MINT_ID.toString()), mints.deleted);
    }

    // The vault answers "no keysets" with an empty set as well as with a 404; both mean there
    // is nothing signing, so both allow the rollback.
    @Test
    @DisplayName("An empty keyset answer also allows the rollback")
    void emptyKeySetAnswerAllowsTheRollback() {
        final RecordingMintVaultClient mints = new RecordingMintVaultClient();

        adapter(new StubKeySetVaultClient(Set.of()), mints).compensate(MINT_ID);

        assertEquals(List.of(MINT_ID.toString()), mints.deleted);
    }

    // If the keysets cannot be read, compensation cannot tell whether the mint signs, so it
    // deletes nothing rather than guessing. It still does not throw: the caller is already on
    // its failure path and must go on to record the failure.
    @Test
    @DisplayName("An unreadable keyset list deletes nothing and does not throw")
    void unreadableKeySetsDeleteNothing() {
        final RecordingMintVaultClient mints = new RecordingMintVaultClient();
        final StubKeySetVaultClient unreachable = StubKeySetVaultClient.failing(
            new ResourceAccessException("vault unreachable"));

        assertDoesNotThrow(() -> adapter(unreachable, mints).compensate(MINT_ID));
        assertTrue(mints.deleted.isEmpty());
    }

    // A mint row that is already gone is the rollback having nothing to do, not a failure.
    @Test
    @DisplayName("A mint row that is already gone is not an error")
    void missingMintRowIsNotAnError() {
        final RecordingMintVaultClient mints = new RecordingMintVaultClient();
        mints.deleteFailure = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
            HttpHeaders.EMPTY, new byte[0], null);

        assertDoesNotThrow(() -> adapter(StubKeySetVaultClient.notFound(), mints).compensate(MINT_ID));
    }

    private static VaultProvisioningAdapter adapter(final KeySetVaultClient keySets,
                                                    final RecordingMintVaultClient mints) {
        return new VaultProvisioningAdapter(new DeterministicKeyGenerator(), () -> keySets,
            () -> {
                throw new AssertionError("compensation never writes keys");
            },
            () -> mints);
    }

    private static KeySetEntity keySet(final String keySetId) {
        final KeySetEntity keySet = new KeySetEntity();
        keySet.setId(UUID.randomUUID());
        keySet.setKeySetId(keySetId);
        keySet.setUnit("sat");
        return keySet;
    }

    /** Answers the keyset lookup the way the vault client does, including by throwing. */
    private static final class StubKeySetVaultClient extends KeySetVaultClient {

        private final Set<KeySetEntity> keySets;
        private final RuntimeException failure;

        private StubKeySetVaultClient(final Set<KeySetEntity> keySets) {
            this(keySets, null);
        }

        private StubKeySetVaultClient(final Set<KeySetEntity> keySets, final RuntimeException failure) {
            this.keySets = keySets;
            this.failure = failure;
        }

        static StubKeySetVaultClient notFound() {
            return failing(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], null));
        }

        static StubKeySetVaultClient failing(final RuntimeException failure) {
            return new StubKeySetVaultClient(Set.of(), failure);
        }

        @Override
        public Set<KeySetEntity> getByMintId(final String mintId) {
            if (failure != null) {
                throw failure;
            }
            return keySets;
        }
    }

    /** Records mint deletes without reaching a vault over the network. */
    private static final class RecordingMintVaultClient extends VaultClient<MintEntity> {

        private final List<String> deleted = new ArrayList<>();
        private RuntimeException deleteFailure;

        private RecordingMintVaultClient() {
            super(MintEntity.class, "http://vault.invalid");
        }

        @Override
        public void delete(final String id) {
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            deleted.add(id);
        }
    }
}
