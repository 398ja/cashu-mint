package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

/**
 * Reads keysets from the shared vault the admin provisions into.
 *
 * <p>Key material is dropped here rather than in a mapper further up, so no layer above
 * this adapter ever holds a private key it could log or serialise by accident. Only the
 * keyset row is read; the keys hanging off it are never fetched.
 */
public class VaultKeySetInventoryAdapter implements KeySetInventoryPort {

    @Override
    public List<VaultKeySet> listByMint(final UUID mintId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        return VaultClientFactory.keySetClient().getByMintId(mintId.toString()).stream()
            .map(VaultKeySetInventoryAdapter::describe)
            .toList();
    }

    private static VaultKeySet describe(final KeySetEntity keySet) {
        return new VaultKeySet(keySet.getKeySetId(), keySet.getUnit(),
            keySet.getCreatedAt(), keySet.isArchived());
    }
}
