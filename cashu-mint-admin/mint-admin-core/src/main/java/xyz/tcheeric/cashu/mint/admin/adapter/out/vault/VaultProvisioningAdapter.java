package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.KeyVaultClient;
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

    public VaultProvisioningAdapter(final DeterministicKeyGenerator keyGenerator) {
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "key generator must not be null");
    }

    @Override
    public void provision(final UUID mintId, final String unit, final List<Integer> denominations) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(denominations, "denominations must not be null");

        final VaultClient<MintEntity> mintClient = VaultClientFactory.getClient(MintEntity.class);
        final KeySetVaultClient keySetClient = VaultClientFactory.keySetClient();
        final KeyVaultClient keyClient = VaultClientFactory.keyClient();

        final MintEntity mintEntity = storeMintEntity(mintClient, mintId);
        final String keySetId = keyGenerator.deriveKeySetId(mintId, unit, denominations);
        final UUID keySetRowId = keyGenerator.deterministicId(mintId, unit, keySetId);
        final KeySetEntity keySetEntity = storeKeySetEntity(keySetClient, keySetRowId, keySetId, unit, mintEntity);
        storeKeyEntities(keyClient, mintId, unit, denominations, keySetEntity);

        log.info("Vault provisioned for mint {} with keyset {}", mintId, keySetId);
    }

    @Override
    public RotationResult rotate(final UUID mintId, final String unit, final List<Integer> denominations,
                                 final String rotationId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(denominations, "denominations must not be null");
        Objects.requireNonNull(rotationId, "rotation id must not be null");

        final VaultClient<MintEntity> mintClient = VaultClientFactory.getClient(MintEntity.class);
        final KeySetVaultClient keySetClient = VaultClientFactory.keySetClient();
        final KeyVaultClient keyClient = VaultClientFactory.keyClient();

        final MintEntity mintEntity = storeMintEntity(mintClient, mintId);

        // Which keysets this rotation replaces, captured before the new one lands
        // so the new keyset is never counted among them.
        final List<String> superseded = keySetClient.getByMintId(mintId.toString()).stream()
            .filter(keySet -> unit.equals(keySet.getUnit()))
            .filter(keySet -> !keySet.isArchived())
            .map(KeySetEntity::getKeySetId)
            .toList();

        final String keySetId = keyGenerator.deriveKeySetId(mintId, unit, denominations, rotationId);
        final UUID keySetRowId = keyGenerator.deterministicId(mintId, unit, keySetId);
        final KeySetEntity keySetEntity = storeKeySetEntity(keySetClient, keySetRowId, keySetId, unit, mintEntity);
        storeKeyEntities(keyClient, mintId, unit, denominations, keySetEntity, rotationId);

        // Archive last: until the replacement exists and can sign, the mint must
        // keep its current keyset, or a failure mid-rotation leaves it unable to issue.
        for (final KeySetEntity keySet : keySetClient.getByMintId(mintId.toString())) {
            if (unit.equals(keySet.getUnit())
                && !keySet.isArchived()
                && !keySetId.equals(keySet.getKeySetId())) {
                keySetClient.archive(keySet.getId().toString());
            }
        }

        log.info("Vault keyset rotated for mint {} unit {}: {} replaces {}",
            mintId, unit, keySetId, superseded);
        return new RotationResult(keySetId, superseded);
    }

    @Override
    public boolean isProvisioned(final UUID mintId) {
        try {
            final VaultClient<MintEntity> mintClient = VaultClientFactory.getClient(MintEntity.class);
            return mintClient.retrieve(mintId.toString()) != null;
        } catch (final Exception e) {
            return false;
        }
    }

    @Override
    public void archive(final UUID mintId) {
        try {
            final KeySetVaultClient keySetClient = VaultClientFactory.keySetClient();
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
            final VaultClient<MintEntity> mintClient = VaultClientFactory.getClient(MintEntity.class);
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

    private void storeKeyEntities(final KeyVaultClient keyClient,
                                   final UUID mintId,
                                   final String unit,
                                   final List<Integer> denominations,
                                   final KeySetEntity keySetEntity) {
        storeKeyEntities(keyClient, mintId, unit, denominations, keySetEntity, null);
    }

    private void storeKeyEntities(final KeyVaultClient keyClient,
                                   final UUID mintId,
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
                        keyClient.store(keyEntity);
                    } catch (final HttpClientErrorException.Conflict e) {
                        log.debug("Key entity for amount {} already exists", amount);
                    }
                }, executor))
                .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }
}
