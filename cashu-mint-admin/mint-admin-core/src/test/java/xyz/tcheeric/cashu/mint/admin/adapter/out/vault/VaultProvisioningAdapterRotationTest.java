package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort.RotationResult;
import xyz.tcheeric.cashu.vault.api.KeyVault;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

/**
 * The order a rotation touches the vault in, which the vault's own
 * one-active-keyset-per-unit invariant leaves no freedom over.
 */
class VaultProvisioningAdapterRotationTest {

    private static final UUID MINT_ID = UUID.fromString("021b8c8e-a6a4-457b-9c29-2bd57c119af7");
    private static final UUID OLD_ROW_ID = UUID.fromString("eddc4d35-a908-3c2d-b70c-52e3d435be65");
    private static final String OLD_KEYSET_ID = "00bb04981c77bc64";
    private static final String UNIT = "sat";
    private static final List<Integer> DENOMINATIONS = List.of(1, 2, 4);

    /**
     * The regression this test exists for. The vault allows a mint one *active* keyset
     * per unit, so storing the replacement while its predecessor is still active is
     * rejected and the rotation fails outright. Archiving must therefore come first.
     */
    @Test
    @DisplayName("A rotation archives the outgoing keyset before storing its replacement")
    void archivesBeforeStoringReplacement() {
        final RecordingKeySetVaultClient vault = new RecordingKeySetVaultClient(activeKeySet());

        rotateWith(vault, new RecordingKeyVault());

        assertEquals(List.of("archive", "store"), vault.calls);
    }

    /**
     * An operator reading the audit trail has to be able to say which keyset replaced
     * which, so both ids travel back out of the rotation.
     */
    @Test
    @DisplayName("A rotation reports the keyset it replaced")
    void reportsSupersededKeySet() {
        final RecordingKeySetVaultClient vault = new RecordingKeySetVaultClient(activeKeySet());

        final RotationResult result = rotateWith(vault, new RecordingKeyVault());

        assertEquals(List.of(OLD_KEYSET_ID), result.previousKeySetIds());
        assertNotEquals(OLD_KEYSET_ID, result.newKeySetId());
    }

    /**
     * The replacement is only a rotation if it can actually sign, so a key is written
     * for every denomination the new keyset covers.
     */
    @Test
    @DisplayName("A rotation writes a key for every denomination")
    void writesKeyPerDenomination() {
        final RecordingKeyVault keyVault = new RecordingKeyVault();

        rotateWith(new RecordingKeySetVaultClient(activeKeySet()), keyVault);

        assertEquals(DENOMINATIONS.size(), keyVault.stored.size());
    }

    /**
     * Archiving first means a failure part-way leaves the mint with nothing able to
     * sign, so the keyset that was archived has to be put back.
     */
    @Test
    @DisplayName("A rotation that cannot store the replacement reinstates the old keyset")
    void reinstatesOldKeySetWhenStoreFails() {
        final KeySetEntity active = activeKeySet();
        final RecordingKeySetVaultClient vault = new RecordingKeySetVaultClient(active);
        vault.failStore = true;

        assertThrows(IllegalStateException.class, () -> rotateWith(vault, new RecordingKeyVault()));

        assertTrue(vault.calls.contains("reinstate"));
        assertFalse(active.isArchived());
    }

    /**
     * A mint the vault holds no keysets for has nothing to supersede, and the client
     * says so by throwing. Rotation provisions a first keyset rather than failing.
     */
    @Test
    @DisplayName("A rotation for a mint with no keysets supersedes nothing")
    void rotatesMintWithoutExistingKeySets() {
        final RecordingKeySetVaultClient vault = new RecordingKeySetVaultClient(null);

        final RotationResult result = rotateWith(vault, new RecordingKeyVault());

        assertTrue(result.previousKeySetIds().isEmpty());
        assertEquals(List.of("store"), vault.calls);
    }

    private RotationResult rotateWith(final KeySetVaultClient keySetClient, final KeyVault keyVault) {
        final var adapter = new VaultProvisioningAdapter(
            new StubKeyGenerator(), () -> keySetClient, () -> keyVault);
        return adapter.rotate(MINT_ID, UNIT, DENOMINATIONS, "rotation-1");
    }

    private static KeySetEntity activeKeySet() {
        final KeySetEntity keySet = new KeySetEntity();
        keySet.setId(OLD_ROW_ID);
        keySet.setKeySetId(OLD_KEYSET_ID);
        keySet.setUnit(UNIT);
        keySet.setArchived(false);
        return keySet;
    }

    /** Records the order in which a rotation archives and stores keysets. */
    private static final class RecordingKeySetVaultClient extends KeySetVaultClient {

        private final KeySetEntity active;
        private final List<String> calls = new ArrayList<>();
        private boolean failStore;

        private RecordingKeySetVaultClient(final KeySetEntity active) {
            this.active = active;
        }

        @Override
        public Set<KeySetEntity> getByMintId(final String mintId) {
            if (active == null) {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                    HttpHeaders.EMPTY, new byte[0], null);
            }
            return Set.of(active);
        }

        @Override
        public KeySetEntity archive(final String id) {
            calls.add("archive");
            active.setArchived(true);
            return active;
        }

        @Override
        public KeySetEntity store(final KeySetEntity entity) {
            // Reinstating the predecessor is a store of the row that was archived,
            // told apart from the replacement by its id.
            if (OLD_ROW_ID.equals(entity.getId())) {
                calls.add("reinstate");
                return entity;
            }
            calls.add("store");
            // The real vault's invariant, reproduced so these tests fail the way
            // production did rather than merely asserting a call order. Restoring
            // the old archive-last ordering turns this into the exact 500 the
            // admin recorded as KEY_ROTATION_FAILED.
            if (active != null && !active.isArchived()) {
                throw new IllegalStateException("duplicate key value violates idx_keyset_unit_mint_active_unq");
            }
            if (failStore) {
                throw new IllegalStateException("vault rejected the keyset");
            }
            return entity;
        }
    }

    /** Collects the keys a rotation writes, without touching a real vault. */
    private static final class RecordingKeyVault implements KeyVault {

        private final List<KeyEntity> stored = Collections.synchronizedList(new ArrayList<>());

        @Override
        public KeyEntity store(final KeyEntity entity) {
            stored.add(entity);
            return entity;
        }

        @Override
        public KeyEntity retrieve(final String id) {
            return null;
        }

        @Override
        public void delete(final String id) {
            // A rotation never deletes a key; archived keysets must go on redeeming.
        }
    }

    /** Derives keyset ids without deriving real key material. */
    private static final class StubKeyGenerator extends DeterministicKeyGenerator {

        @Override
        public String deriveKeySetId(final UUID mintId, final String unit,
                                     final List<Integer> denominations, final String rotationId) {
            return "00new" + Integer.toHexString(String.valueOf(rotationId).hashCode());
        }

        @Override
        public String derivePrivateKeyHex(final UUID mintId, final String unit, final int amount,
                                          final String rotationId) {
            return String.format("%064x", amount);
        }
    }
}
