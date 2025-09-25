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
        List<Mint> list = new ArrayList<>();
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

            var keySetClient = VaultClientFactory.keySetClient();
            try {
                if (keySetClient.getByKeySetId(keySetId) != null) {
                    log.debug("PreloadMintLoadService: vault already contains keyset {}", keySetId);
                    vaultSeeded.set(true);
                    return;
                }
            } catch (Exception ignored) {
                // fall through to seeding logic
            }

            MintEntity mintEntity = new MintEntity();
            mintEntity.setId(mintUuid);

            KeySetEntity keySetEntity = new KeySetEntity();
            if (keySetRowId != null && !keySetRowId.isBlank()) {
                keySetEntity.setId(UUID.fromString(keySetRowId));
            }
            keySetEntity.setKeySetId(keySetId);
            keySetEntity.setUnit(unit);
            keySetEntity.setMint(mintEntity);

            for (JsonNode k : root.withArray("keys")) {
                KeyEntity keyEntity = new KeyEntity();
                String keyId = k.path("id").asText(null);
                if (keyId != null && !keyId.isBlank()) {
                    keyEntity.setId(UUID.fromString(keyId));
                }
                keyEntity.setAmount(BigInteger.valueOf(k.path("amount").asInt()));
                keyEntity.setPrivateKey(k.path("privateKeyHex").asText());
                keyEntity.setKeySet(keySetEntity);
                keySetEntity.getKeys().add(keyEntity);
            }

            mintEntity.getKeySets().add(keySetEntity);

            VaultClientFactory.getClient(MintEntity.class).store(mintEntity);
            log.info("PreloadMintLoadService: seeded vault with mint {} keyset {}", mintUuid, keySetId);
            vaultSeeded.set(true);
        } catch (Exception e) {
            log.warn("PreloadMintLoadService: failed to seed vault from preload JSON", e);
                vaultSeeded.set(false);
            }
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
