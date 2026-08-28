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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "mint.preload.enabled", havingValue = "true", matchIfMissing = true)
public class PreloadMintLoadService implements MintLoadService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Resource preloadJson;

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
