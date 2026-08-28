package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.KeyVault;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.VaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

/**
 * Provisions cryptographic vault material via the vault REST API.
 * Creates mint, keyset, and key entities using deterministic identifiers.
 */
public class VaultProvisioningAdapter implements VaultProvisioningPort {

    private static final Logger log = LoggerFactory.getLogger(VaultProvisioningAdapter.class);

    private final DeterministicKeyGenerator keyGenerator;
    private final Supplier<KeySetVaultClient> keySetClientSupplier;
    private final Supplier<KeyVault> keyVaultSupplier;
    private final Supplier<VaultClient<MintEntity>> mintClientSupplier;

    public VaultProvisioningAdapter(final DeterministicKeyGenerator keyGenerator) {
        this(keyGenerator, VaultClientFactory::keySetClient, VaultClientFactory::keyVault,
            () -> VaultClientFactory.getClient(MintEntity.class));
    }

    /** Seam for the test that pins the order a rotation archives and provisions in. */
    VaultProvisioningAdapter(final DeterministicKeyGenerator keyGenerator,
                             final Supplier<KeySetVaultClient> keySetClientSupplier,
                             final Supplier<KeyVault> keyVaultSupplier,
                             final Supplier<VaultClient<MintEntity>> mintClientSupplier) {
        this.mintClientSupplier = Objects.requireNonNull(mintClientSupplier,
            "mint client supplier must not be null");
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "key generator must not be null");
        this.keySetClientSupplier = Objects.requireNonNull(keySetClientSupplier,
            "keyset client supplier must not be null");
        this.keyVaultSupplier = Objects.requireNonNull(keyVaultSupplier,
            "key vault supplier must not be null");
    }

    @Override
    public void provision(final UUID mintId, final String unit, final List<Integer> denominations) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(denominations, "denominations must not be null");

        final VaultClient<MintEntity> mintClient = mintClientSupplier.get();
        final KeySetVaultClient keySetClient = keySetClientSupplier.get();

        final MintEntity mintEntity = storeMintEntity(mintClient, mintId);
        final String keySetId = keyGenerator.deriveKeySetId(mintId, unit, denominations);

        // A mint may already hold an active keyset for this unit, provisioned by
        // something other than this saga. Adding a second one would leave the unit
        // with two keysets claiming to sign, and the next rotation would archive
        // both and be unable to say which it replaced. Provisioning is meant to
        // establish a keyset, not to add one, so an existing active keyset stands.
        final Optional<KeySetEntity> active = readKeySets(keySetClient, mintId).stream()
            .filter(keySet -> unit.equals(keySet.getUnit()))
            .filter(keySet -> !keySet.isArchived())
            .findFirst();
        if (active.isPresent() && !keySetId.equals(active.get().getKeySetId())) {
            log.info("Mint {} already holds active keyset {} for unit {}; leaving it in place",
                mintId, active.get().getKeySetId(), unit);
            return;
        }

        final UUID keySetRowId = keyGenerator.deterministicId(mintId, unit, keySetId);
        final KeySetEntity keySetEntity = storeKeySetEntity(keySetClient, keySetRowId, keySetId, unit, mintEntity);
        storeKeyEntities(mintId, unit, denominations, keySetEntity);

        log.info("Vault provisioned for mint {} with keyset {}", mintId, keySetId);
    }

    @Override
    public RotationResult rotate(final UUID mintId, final String unit, final List<Integer> denominations,
                                 final String rotationId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(denominations, "denominations must not be null");
        Objects.requireNonNull(rotationId, "rotation id must not be null");

        final VaultClient<MintEntity> mintClient = mintClientSupplier.get();
        final KeySetVaultClient keySetClient = keySetClientSupplier.get();

        final MintEntity mintEntity = storeMintEntity(mintClient, mintId);

        final String keySetId = keyGenerator.deriveKeySetId(mintId, unit, denominations, rotationId);

        // Which keysets this rotation replaces. The new keyset is excluded by id
        // rather than by ordering: on a redelivery it already exists and is still
        // unarchived, and would otherwise be recorded as its own predecessor.
        final List<KeySetEntity> superseded = readKeySets(keySetClient, mintId).stream()
            .filter(keySet -> unit.equals(keySet.getUnit()))
            .filter(keySet -> !keySet.isArchived())
            .filter(keySet -> !keySetId.equals(keySet.getKeySetId()))
            .toList();

        // Archive first. The vault permits a mint only one *active* keyset per unit
        // (idx_keyset_unit_mint_active_unq), so inserting the replacement while its
        // predecessor is still active is rejected outright and no rotation can ever
        // complete. Ordering is therefore forced by the invariant, not chosen: the
        // brief window where the unit has no active keyset is the cost of never
        // having two keysets that both claim to sign.
        archiveAll(keySetClient, superseded);

        final UUID keySetRowId = keyGenerator.deterministicId(mintId, unit, keySetId);
        try {
            final KeySetEntity keySetEntity =
                storeKeySetEntity(keySetClient, keySetRowId, keySetId, unit, mintEntity);
            storeKeyEntities(mintId, unit, denominations, keySetEntity, rotationId);
        } catch (final Exception e) {
            // The predecessors are already archived, so failing here would leave the
            // mint with no keyset able to sign. Reinstate them before giving up.
            reinstate(keySetClient, superseded);
            throw new IllegalStateException(
                "Rotation failed for mint " + mintId + "; keyset " + keySetId
                    + " was not provisioned and the previous keyset was reinstated", e);
        }

        final List<String> supersededIds = superseded.stream().map(KeySetEntity::getKeySetId).toList();
        log.info("Vault keyset rotated for mint {} unit {}: {} replaces {}",
            mintId, unit, keySetId, supersededIds);
        return new RotationResult(keySetId, supersededIds);
    }

    private void archiveAll(final KeySetVaultClient keySetClient, final List<KeySetEntity> keySets) {
        for (final KeySetEntity keySet : keySets) {
            keySetClient.archive(keySet.getId().toString());
        }
    }

    /**
     * The mint's keysets, with "this mint holds none" answered as an empty set.
     *
     * <p>The client signals that by throwing rather than by answering empty: the vault's
     * 404 surfaces as NotFound and a 200 with an empty body as IllegalArgumentException.
     * A mint with nothing to supersede is a rotation that provisions a first keyset, not
     * a failure. Every other fault has a different type and still propagates.
     */
    private Set<KeySetEntity> readKeySets(final KeySetVaultClient keySetClient, final UUID mintId) {
        try {
            return keySetClient.getByMintId(mintId.toString());
        } catch (final HttpClientErrorException.NotFound | IllegalArgumentException e) {
            log.debug("Vault holds no keysets for mint {}", mintId);
            return Set.of();
        }
    }

    /**
     * Returns keysets to active after a rotation failed partway, so the mint is
     * left able to sign with the keyset it started with.
     */
    private void reinstate(final KeySetVaultClient keySetClient, final List<KeySetEntity> keySets) {
        for (final KeySetEntity keySet : keySets) {
            try {
                keySet.setArchived(false);
                keySetClient.store(keySet);
                log.warn("Rotation compensation: reinstated keyset {}", keySet.getKeySetId());
            } catch (final Exception e) {
                log.error("Rotation compensation failed; mint has no active keyset for keyset {}",
                    keySet.getKeySetId(), e);
            }
        }
    }

    @Override
    public boolean isProvisioned(final UUID mintId) {
        try {
            final VaultClient<MintEntity> mintClient = mintClientSupplier.get();
            return mintClient.retrieve(mintId.toString()) != null;
        } catch (final Exception e) {
            return false;
        }
    }

    @Override
    public void archive(final UUID mintId) {
        try {
            final KeySetVaultClient keySetClient = keySetClientSupplier.get();
            final var keySets = keySetClient.getByMintId(mintId.toString());
            for (final KeySetEntity keySet : keySets) {
                keySetClient.archive(keySet.getId().toString());
            }
            log.info("Vault keysets archived for mint {}", mintId);
        } catch (final Exception e) {
            log.warn("Failed to archive vault keysets for mint {}: {}", mintId, e.getMessage());
            throw new RuntimeException("Vault archive failed for mint " + mintId, e);
        }
    }

    @Override
    public void compensate(final UUID mintId) {
        try {
            final VaultClient<MintEntity> mintClient = mintClientSupplier.get();
            mintClient.delete(mintId.toString());
            log.info("Vault compensation: deleted mint entity {}", mintId);
        } catch (final HttpClientErrorException.NotFound e) {
            log.debug("Vault compensation: mint entity {} not found, nothing to clean up", mintId);
        } catch (final Exception e) {
            log.warn("Vault compensation failed for mint {}: {}", mintId, e.getMessage());
        }
    }

    private MintEntity storeMintEntity(final VaultClient<MintEntity> mintClient, final UUID mintId) {
        final MintEntity entity = new MintEntity();
        entity.setId(mintId);
        try {
            return mintClient.store(entity);
        } catch (final HttpClientErrorException.Conflict e) {
            log.debug("Mint entity {} already exists, treating as success", mintId);
            return mintClient.retrieve(mintId.toString());
        }
    }

    private KeySetEntity storeKeySetEntity(final KeySetVaultClient keySetClient,
                                            final UUID keySetRowId,
                                            final String keySetId,
                                            final String unit,
                                            final MintEntity mintEntity) {
        final KeySetEntity entity = new KeySetEntity();
        entity.setId(keySetRowId);
        entity.setKeySetId(keySetId);
        entity.setUnit(unit);
        entity.setMint(mintEntity);
        try {
            return keySetClient.store(entity);
        } catch (final HttpClientErrorException.Conflict e) {
            log.debug("KeySet {} already exists, treating as success", keySetId);
            return keySetClient.getByKeySetId(keySetId);
        }
    }

    private void storeKeyEntities(final UUID mintId,
                                   final String unit,
                                   final List<Integer> denominations,
                                   final KeySetEntity keySetEntity) {
        storeKeyEntities(mintId, unit, denominations, keySetEntity, null);
    }

    private void storeKeyEntities(final UUID mintId,
                                   final String unit,
                                   final List<Integer> denominations,
                                   final KeySetEntity keySetEntity,
                                   final String rotationId) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<CompletableFuture<Void>> futures = denominations.stream()
                .map(amount -> CompletableFuture.runAsync(() -> {
                    // The row id must carry the rotation too, or a rotated key would
                    // collide with the one it replaces for the same mint/unit/amount.
                    final String keyIdSource = rotationId == null
                        ? String.valueOf(amount)
                        : amount + "|" + rotationId;
                    final UUID keyId = keyGenerator.deterministicId(mintId, unit, keyIdSource);
                    final String privateKeyHex =
                        keyGenerator.derivePrivateKeyHex(mintId, unit, amount, rotationId);
                    final KeyEntity keyEntity = new KeyEntity();
                    keyEntity.setId(keyId);
                    keyEntity.setAmount(BigInteger.valueOf(amount));
                    keyEntity.setPrivateKey(privateKeyHex);
                    keyEntity.setKeySet(keySetEntity);
                    try {
                        // Through the backend-aware vault rather than the REST client.
                        // Under the default HashiCorp backend this writes the secret to
                        // HashiCorp and stamps the row with the path it used; t_key has
                        // no column for a private key, so storing the row directly would
                        // persist a key that exists nowhere. It is also the route the
                        // mint reads back through.
                        keyVaultSupplier.get().store(keyEntity);
                    } catch (final HttpClientErrorException.Conflict e) {
                        log.debug("Key entity for amount {} already exists", amount);
                    } catch (final CashuErrorException e) {
                        throw new IllegalStateException(
                            "Failed to store key for amount " + amount + " of mint " + mintId, e);
                    }
                }, executor))
                .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }
}
