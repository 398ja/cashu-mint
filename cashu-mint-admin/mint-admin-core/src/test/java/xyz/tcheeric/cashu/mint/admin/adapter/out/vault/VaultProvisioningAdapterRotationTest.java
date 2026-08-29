package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
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
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.KeyVault;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.VaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

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
     * Writing a key per denomination is only worth anything if the mint can then find
     * it the way it signs: by amount within the new keyset. A rotation that wrote keys
     * against the wrong keyset would still satisfy a count, so the lookup is asserted.
     */
    @Test
    @DisplayName("A rotation's keys are retrievable by amount under the new keyset")
    void writesKeysRetrievableByAmountUnderNewKeySet() throws CashuErrorException {
        final RecordingKeyVault keyVault = new RecordingKeyVault();

        final RotationResult result = rotateWith(new RecordingKeySetVaultClient(activeKeySet()), keyVault);

        for (final int denomination : DENOMINATIONS) {
            final KeyEntity key = keyVault.retrieveByAmount(
                BigInteger.valueOf(denomination), result.newKeySetId());
            assertEquals(BigInteger.valueOf(denomination), key.getAmount());
        }
    }

    /**
     * The mint asks for amounts a keyset does not cover, and the vault says so by
     * throwing rather than by handing back a null the caller would dereference.
     */
    @Test
    @DisplayName("An amount the new keyset does not cover is reported as missing")
    void reportsMissingKeyForUncoveredAmount() {
        final RecordingKeyVault keyVault = new RecordingKeyVault();

        final RotationResult result = rotateWith(new RecordingKeySetVaultClient(activeKeySet()), keyVault);

        assertThrows(CashuErrorException.class,
            () -> keyVault.retrieveByAmount(BigInteger.valueOf(8), result.newKeySetId()));
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

    /**
   * Provisioning runs against a mint whose keyset something else already established
   * (in the dev and E2E stacks, the preload seeder). Adding a second active keyset for
   * the unit would leave two keysets claiming to sign, and the next rotation would
   * archive both and be unable to name the one it replaced.
   */
  @Test
  @DisplayName("Provisioning leaves an existing active keyset alone")
  void provisioningKeepsAnExistingActiveKeySet() {
    final RecordingKeySetVaultClient vault = new RecordingKeySetVaultClient(activeKeySet());

    new VaultProvisioningAdapter(
            new StubKeyGenerator(), () -> vault, RecordingKeyVault::new, StubMintVaultClient::new)
        .provision(MINT_ID, UNIT, DENOMINATIONS);

    assertTrue(vault.calls.isEmpty());
  }

  private RotationResult rotateWith(final KeySetVaultClient keySetClient, final KeyVault keyVault) {
        final var adapter = new VaultProvisioningAdapter(
            new StubKeyGenerator(), () -> keySetClient, () -> keyVault, StubMintVaultClient::new);
        return adapter.rotate(MINT_ID, UNIT, DENOMINATIONS, "rotation-1");
    }

    /** Answers for the mint row without reaching a vault over the network. */
    private static final class StubMintVaultClient extends VaultClient<MintEntity> {

        private StubMintVaultClient() {
            super(MintEntity.class, "http://vault.invalid");
        }

        @Override
        public MintEntity store(final MintEntity entity) {
            return entity;
        }

        @Override
        public MintEntity retrieve(final String id) {
            final MintEntity entity = new MintEntity();
            entity.setId(UUID.fromString(id));
            return entity;
        }
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
        public KeyEntity retrieve(final String id) throws CashuErrorException {
            return stored.stream()
                .filter(key -> String.valueOf(key.getId()).equals(id))
                .findFirst()
                .orElseThrow(() -> new CashuErrorException("Key not found"));
        }

        /**
         * The lookup the mint signs with, answered from the keys the rotation wrote.
         * The real vault matches on amount within one keyset and throws when nothing
         * matches, so a fake that answered null would let a rotation that wrote no
         * usable key still look like it had.
         */
        @Override
        public KeyEntity retrieveByAmount(final BigInteger amount, final String keySetId)
            throws CashuErrorException {
            return stored.stream()
                .filter(key -> amount.equals(key.getAmount()))
                .filter(key -> key.getKeySet() != null
                    && keySetId.equals(key.getKeySet().getKeySetId()))
                .findFirst()
                .orElseThrow(() -> new CashuErrorException(
                    "Key not found for amount: " + amount + " and keySetId: " + keySetId));
        }

        /**
         * A key is retired by marking the row, never by removing it. A rotation does
         * not archive keys itself, but a fake that silently succeeded here would hide
         * a rotation that started doing so.
         */
        @Override
        public KeyEntity archive(final String id) throws CashuErrorException {
            final KeyEntity key = retrieve(id);
            key.setArchived(true);
            return key;
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
