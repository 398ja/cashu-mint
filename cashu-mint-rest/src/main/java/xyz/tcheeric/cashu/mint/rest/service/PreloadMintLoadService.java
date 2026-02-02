package xyz.tcheeric.cashu.mint.rest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "mint.preload.enabled", havingValue = "true", matchIfMissing = true)
public class PreloadMintLoadService implements MintLoadService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Resource preloadJson;
    private final Object vaultSeedLock = new Object();
    private final AtomicBoolean vaultSeeded = new AtomicBoolean(false);

    public PreloadMintLoadService(@Value("${mint.preload.json.input:scripts/preload-test-data.json}") Resource preloadJson) {
        this.preloadJson = preloadJson;
    }

    @Override
    public Mint load(@NonNull UUID mintId, boolean archive) throws CashuErrorException {
        try (InputStream in = resolveInput()) {
            JsonNode root = MAPPER.readTree(in);
            // Expected structure: { mintId: UUID, keySetId: string, unit: string, keys: [ { amount, privateKeyHex } ] }
            UUID fileMintId = UUID.fromString(root.path("mintId").asText());
            String unit = root.path("unit").asText();
            String externalKeySetId = root.path("keySetId").asText(null);
            seedVaultIfNeeded(root);

            Keys keys = new Keys();
            for (JsonNode k : root.withArray("keys")) {
                int amount = k.path("amount").asInt();
                String privateHex = k.path("privateKeyHex").asText();
                PrivateKey priv = PrivateKey.fromString(privateHex);
                keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(priv));
            }

            // Prefer explicit keySetId from JSON to keep alignment with seeded DB; fallback to derivation
            String keySetId = (externalKeySetId != null && !externalKeySetId.isBlank())
                    ? externalKeySetId
                    : KeySetDerivation.getId(keys.values());
            KeySet keySet = KeySet.builder()
                    .id(keySetId)
                    .unit(unit)
                    .keys(keys)
                    .build();

            Mint mint = new Mint(fileMintId.toString());
            mint.addKeySet(keySet);
            if (log.isDebugEnabled()) {
                log.debug("PreloadMintLoadService: loaded mintId={} keySetId={} unit={}",
                        mint.getId(), keySetId, unit);
            }
            return mint;
        } catch (IOException e) {
            throw new CashuErrorException("preload_json_read_error");
        } catch (RuntimeException e) {
            log.warn("Failed to load preload JSON", e);
            throw new CashuErrorException("preload_json_parse_error");
        }
    }

    @Override
    public List<Mint> load(boolean archive) throws CashuErrorException {
        List<Mint> list = new ArrayList<>(1);  // Single mint in list
        Mint mint = load(UUID.randomUUID(), archive);
        list.add(mint);
        return list;
    }

    private void seedVaultIfNeeded(JsonNode root) {
        if (vaultSeeded.get()) {
            return;
        }
        synchronized (vaultSeedLock) {
            if (vaultSeeded.get()) {
                return;
            }
            try {
                UUID mintUuid = UUID.fromString(root.path("mintId").asText());
                String unit = root.path("unit").asText();
                String keySetId = root.path("keySetId").asText();
                String keySetRowId = root.path("keySetRowId").asText(null);

                var mintClient = VaultClientFactory.getClient(MintEntity.class);
                var keySetClient = VaultClientFactory.keySetClient();
                MintEntity storedMint = null;
                try {
                    MintEntity existingMint = mintClient.retrieve(mintUuid.toString());
                    if (existingMint != null) {
                        storedMint = existingMint;
                        log.debug("PreloadMintLoadService: mint {} already present, verifying keys", mintUuid);
                        try {
                            KeySetEntity existingKeySet = keySetClient.getByKeySetId(keySetId);
                            if (existingKeySet != null) {
                                ensureVaultKeys(existingKeySet, root);
                                vaultSeeded.set(true);
                                return;
                            }
                        } catch (Exception ignored) {
                            // fall through to seeding logic so missing keysets are created below
                        }
                    }
                } catch (HttpClientErrorException.NotFound ignored) {
                    // mint not there yet
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode() == org.springframework.http.HttpStatus.CONFLICT) {
                        log.debug("PreloadMintLoadService: mint {} already present (conflict), skipping seed", mintUuid);
                        vaultSeeded.set(true);
                        return;
                    }
                    throw e;
                }

                KeySetEntity existing;
                try {
                    existing = keySetClient.getByKeySetId(keySetId);
                    if (existing != null) {
                        log.debug("PreloadMintLoadService: keyset {} already present, verifying keys", keySetId);
                        ensureVaultKeys(existing, root);
                        vaultSeeded.set(true);
                        return;
                    }
                } catch (Exception ignored) {
                    // fall through to full seeding logic
                }

                MintEntity mintEntity = storedMint;
                if (mintEntity == null) {
                    mintEntity = new MintEntity();
                    mintEntity.setId(mintUuid);
                    try {
                        mintEntity = mintClient.store(mintEntity);
                    } catch (HttpClientErrorException.Conflict conflict) {
                        mintEntity = mintClient.retrieve(mintUuid.toString());
                    }
                }

                KeySetEntity keySetEntity = new KeySetEntity();
                if (keySetRowId != null && !keySetRowId.isBlank()) {
                    keySetEntity.setId(UUID.fromString(keySetRowId));
                }
                keySetEntity.setKeySetId(keySetId);
                keySetEntity.setUnit(unit);
                keySetEntity.setMint(mintEntity);

                java.util.LinkedHashSet<KeyEntity> keysToSeed = new java.util.LinkedHashSet<>();
                for (JsonNode k : root.withArray("keys")) {
                    KeyEntity keyEntity = new KeyEntity();
                    String keyId = k.path("id").asText(null);
                    if (keyId != null && !keyId.isBlank()) {
                        keyEntity.setId(UUID.fromString(keyId));
                    }
                    keyEntity.setAmount(new BigInteger(k.path("amount").asText()));
                    keyEntity.setPrivateKey(k.path("privateKeyHex").asText());
                    keysToSeed.add(keyEntity);
                }

                KeySetEntity storedKeySet;
                try {
                    storedKeySet = keySetClient.store(keySetEntity);
                } catch (HttpClientErrorException.Conflict conflict) {
                    storedKeySet = keySetClient.getByKeySetId(keySetId);
                }
                for (KeyEntity keyEntity : keysToSeed) {
                    keyEntity.setKeySet(storedKeySet);
                    try {
                        VaultClientFactory.keyClient().store(keyEntity);
                    } catch (HttpClientErrorException.Conflict conflict) {
                        // Key already present; nothing to seed for this amount
                    }
                }

                log.info("PreloadMintLoadService: seeded vault with mint {} keyset {} ({} keys)",
                        mintUuid, keySetId, keysToSeed.size());
                vaultSeeded.set(true);
            } catch (Exception e) {
                if (e instanceof HttpClientErrorException.Conflict conflict) {
                    log.debug("PreloadMintLoadService: mint {} already present, skipping seed", root.path("mintId").asText());
                    vaultSeeded.set(true);
                    return;
                }
                log.warn("PreloadMintLoadService: failed to seed vault from preload JSON", e);
                vaultSeeded.set(false);
            }
        }
    }

    private void ensureVaultKeys(KeySetEntity keySetEntity, JsonNode root) {
        if (keySetEntity == null) {
            return;
        }
        try {
            var keyClient = VaultClientFactory.keyClient();
            var existingKeys = keyClient.getKeysByKeySetId(keySetEntity.getId().toString());
            java.util.Map<java.math.BigInteger, KeyEntity> byAmount = new java.util.HashMap<>();
            if (existingKeys != null) {
                for (KeyEntity key : existingKeys) {
                    byAmount.put(key.getAmount(), key);
                }
            }

            for (JsonNode node : root.withArray("keys")) {
                java.math.BigInteger amount = new java.math.BigInteger(node.path("amount").asText());
                if (byAmount.containsKey(amount)) {
                    continue;
                }
                KeyEntity newKey = new KeyEntity();
                String keyId = node.path("id").asText(null);
                if (keyId != null && !keyId.isBlank()) {
                    newKey.setId(UUID.fromString(keyId));
                }
                newKey.setAmount(amount);
                newKey.setPrivateKey(node.path("privateKeyHex").asText());
                newKey.setKeySet(keySetEntity);
                VaultClientFactory.keyClient().store(newKey);
                log.info("PreloadMintLoadService: added missing key amount={} for keyset {}", amount, keySetEntity.getKeySetId());
            }
        } catch (Exception e) {
            log.warn("PreloadMintLoadService: failed to ensure vault keys for keyset {}", keySetEntity.getKeySetId(), e);
        }
    }

    private InputStream resolveInput() throws IOException {
        if (preloadJson.exists()) {
            return preloadJson.getInputStream();
        }
        // Try classpath fallback
        InputStream in = getClass().getClassLoader().getResourceAsStream("scripts/preload-test-data.json");
        if (in != null) return in;
        throw new IOException("Preload JSON not found: " + preloadJson);
    }
}
