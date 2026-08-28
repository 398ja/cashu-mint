package xyz.tcheeric.cashu.mint.rest.boot;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

/**
 * Seeds the shared vault with the dev keyset described by the preload JSON.
 *
 * <p>Seeding is separate from serving on purpose. A vault-backed mint reads its
 * keysets from the vault, so it needs the vault to already hold one; but the code
 * that used to seed it lived inside {@code PreloadMintLoadService}, which only runs
 * when that service is also answering requests. Disabling preload to make the mint
 * vault-backed therefore took the bootstrap data with it and left the mint serving
 * nothing.
 *
 * <p>Writes go through {@link VaultClientFactory#keyVault()} rather than the REST
 * key client: under the HashiCorp backend the secret is written to HashiCorp and the
 * row stamped with the path it used. {@code t_key} has no column for a private key,
 * so a REST write would persist a key that exists nowhere.
 *
 * <p>Idempotent: an already-seeded vault is left alone, so this is safe on restart.
 */
@Slf4j
@Component
// Before DevKeysetStartupCheck, which reports on the keysets this seeds.
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "mint.preload.seed.enabled", havingValue = "true", matchIfMissing = true)
public class VaultPreloadSeeder implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Resource preloadJson;

    public VaultPreloadSeeder(
            @Value("${mint.preload.json.input:scripts/preload-test-data.json}") final Resource preloadJson) {
        this.preloadJson = preloadJson;
    }

    @Override
    public void run(final ApplicationArguments args) {
        try (InputStream in = resolveInput()) {
            seed(MAPPER.readTree(in));
        } catch (final IOException e) {
            log.debug("No preload JSON to seed the vault from: {}", e.getMessage());
        } catch (final RuntimeException e) {
            log.warn("Failed to seed the vault from preload JSON", e);
        }
    }

    private void seed(final JsonNode root) {
        final UUID mintId = UUID.fromString(root.path("mintId").asText());
        final String keySetId = root.path("keySetId").asText();
        if (keySetExists(keySetId)) {
            log.debug("Vault already holds keyset {}, nothing to seed", keySetId);
            return;
        }

        final MintEntity mint = storeMint(mintId);
        final KeySetEntity keySet = storeKeySet(root, mint);
        storeKeys(root, keySet);
        log.info("Seeded the vault with mint {} keyset {}", mintId, keySetId);
    }

    private boolean keySetExists(final String keySetId) {
        try {
            return VaultClientFactory.keySetClient().getByKeySetId(keySetId) != null;
        } catch (final Exception e) {
            return false;
        }
    }

    private MintEntity storeMint(final UUID mintId) {
        final MintEntity entity = new MintEntity();
        entity.setId(mintId);
        try {
            return VaultClientFactory.getClient(MintEntity.class).store(entity);
        } catch (final HttpClientErrorException.Conflict e) {
            return VaultClientFactory.getClient(MintEntity.class).retrieve(mintId.toString());
        }
    }

    private KeySetEntity storeKeySet(final JsonNode root, final MintEntity mint) {
        final String keySetId = root.path("keySetId").asText();
        final KeySetEntity entity = new KeySetEntity();
        final String rowId = root.path("keySetRowId").asText(null);
        if (rowId != null && !rowId.isBlank()) {
            entity.setId(UUID.fromString(rowId));
        }
        entity.setKeySetId(keySetId);
        entity.setUnit(root.path("unit").asText());
        entity.setMint(mint);
        try {
            return VaultClientFactory.keySetClient().store(entity);
        } catch (final HttpClientErrorException.Conflict e) {
            return VaultClientFactory.keySetClient().getByKeySetId(keySetId);
        }
    }

    private void storeKeys(final JsonNode root, final KeySetEntity keySet) {
        for (final KeyEntity key : readKeys(root)) {
            key.setKeySet(keySet);
            try {
                VaultClientFactory.keyVault().store(key);
            } catch (final HttpClientErrorException.Conflict e) {
                log.debug("Key for amount {} already present", key.getAmount());
            } catch (final CashuErrorException e) {
                // A keyset missing a denomination cannot sign for it, so this is
                // reported rather than left to surface later as a signing failure.
                throw new IllegalStateException(
                        "Failed to seed key for amount " + key.getAmount(), e);
            }
        }
    }

    private Set<KeyEntity> readKeys(final JsonNode root) {
        final Set<KeyEntity> keys = new LinkedHashSet<>();
        for (final JsonNode node : root.withArray("keys")) {
            final KeyEntity key = new KeyEntity();
            final String id = node.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                key.setId(UUID.fromString(id));
            }
            key.setAmount(new BigInteger(node.path("amount").asText()));
            key.setPrivateKey(node.path("privateKeyHex").asText());
            keys.add(key);
        }
        return keys;
    }

    private InputStream resolveInput() throws IOException {
        if (preloadJson.exists()) {
            return preloadJson.getInputStream();
        }
        final InputStream in =
                getClass().getClassLoader().getResourceAsStream("scripts/preload-test-data.json");
        if (in != null) {
            return in;
        }
        throw new IOException("Preload JSON not found: " + preloadJson);
    }
}
