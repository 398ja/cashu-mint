package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.util.List;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.KeyVaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
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
    private final Supplier<KeyVaultClient> keyClient;

    public VaultKeySetInventoryAdapter() {
        this(VaultClientFactory::keySetClient, VaultClientFactory::keyClient);
    }

    /** Seam for the test that pins how a vault holding no keysets is reported. */
    VaultKeySetInventoryAdapter(final Supplier<KeySetVaultClient> keySetClient,
                                final Supplier<KeyVaultClient> keyClient) {
        this.keySetClient = Objects.requireNonNull(keySetClient, "keyset client must not be null");
        this.keyClient = Objects.requireNonNull(keyClient, "key client must not be null");
    }

    @Override
    public List<VaultKeySet> listByMint(final UUID mintId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        return readByMint(mintId).stream()
            .map(VaultKeySetInventoryAdapter::describe)
            .toList();
    }

    /**
     * The mint's keysets, with "this mint holds none" translated to an empty set.
     *
     * <p>The vault client signals that by throwing rather than by answering an empty set, and
     * it has two ways of doing it: the vault answers 404, which the client's plain RestTemplate
     * raises as NotFound, and a 200 carrying an empty body raises IllegalArgumentException. A
     * mint nobody has provisioned yet holds none, and that is an answer; translating both here
     * keeps it from reaching an operator as a vault failure. Every other fault is a different
     * type and still propagates.
     */
    private Set<KeySetEntity> readByMint(final UUID mintId) {
        try {
            return keySetClient.get().getByMintId(mintId.toString());
        } catch (final HttpClientErrorException.NotFound | IllegalArgumentException e) {
            log.debug("Vault holds no keysets for mint {}", mintId);
            return Set.of();
        }
    }

    @Override
    public List<Denomination> listDenominations(final UUID mintId, final String keySetId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        Objects.requireNonNull(keySetId, "keyset id must not be null");
        // The keys endpoint is addressed by the vault's own row id, not by the keyset id
        // wallets name, so the mint's keysets are read first and the wanted one picked out
        // of them. Going through the mint is what scopes the lookup to it.
        final Optional<KeySetEntity> keySet = readByMint(mintId).stream()
            .filter(candidate -> keySetId.equals(candidate.getKeySetId()))
            .findFirst();
        if (keySet.isEmpty()) {
            log.debug("Mint {} holds no keyset {}", mintId, keySetId);
            return List.of();
        }
        final Set<KeyEntity> keys = keyClient.get().getKeysByKeySetId(keySet.get().getId().toString());
        return keys.stream()
            .sorted(Comparator.comparing(KeyEntity::getAmount))
            .map(key -> new Denomination(key.getAmount(), key.getVaultPath()))
            .toList();
    }

    private static VaultKeySet describe(final KeySetEntity keySet) {
        return new VaultKeySet(keySet.getKeySetId(), keySet.getUnit(),
            keySet.getCreatedAt(), keySet.isArchived(), keySet.getInputFeePpk());
    }
}
