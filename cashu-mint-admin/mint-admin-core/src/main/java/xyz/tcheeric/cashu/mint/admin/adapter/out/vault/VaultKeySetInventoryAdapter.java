package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

/**
 * Reads keysets from the shared vault the admin provisions into.
 *
 * <p>Key material is dropped here rather than in a mapper further up, so no layer above
 * this adapter ever holds a private key it could log or serialise by accident. Only the
 * keyset row is read; the keys hanging off it are never fetched.
 */
public class VaultKeySetInventoryAdapter implements KeySetInventoryPort {

    private static final Logger log = LoggerFactory.getLogger(VaultKeySetInventoryAdapter.class);

    private final Supplier<KeySetVaultClient> keySetClient;

    public VaultKeySetInventoryAdapter() {
        this(VaultClientFactory::keySetClient);
    }

    /** Seam for the test that pins how a vault holding no keysets is reported. */
    VaultKeySetInventoryAdapter(final Supplier<KeySetVaultClient> keySetClient) {
        this.keySetClient = Objects.requireNonNull(keySetClient, "keyset client must not be null");
    }

    @Override
    public List<VaultKeySet> listByMint(final UUID mintId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        final Set<KeySetEntity> keySets;
        try {
            keySets = keySetClient.get().getByMintId(mintId.toString());
        } catch (final IllegalArgumentException e) {
            // The vault client reports "this mint holds no keysets" by throwing rather than
            // by answering an empty set. A mint nobody has provisioned yet holds none, and
            // that is an answer; translating it here keeps it from reaching an operator as
            // a vault failure. Transport faults are a different type and still propagate.
            log.debug("Vault holds no keysets for mint {}", mintId);
            return List.of();
        }
        return keySets.stream()
            .map(VaultKeySetInventoryAdapter::describe)
            .toList();
    }

    private static VaultKeySet describe(final KeySetEntity keySet) {
        return new VaultKeySet(keySet.getKeySetId(), keySet.getUnit(),
            keySet.getCreatedAt(), keySet.isArchived());
    }
}
