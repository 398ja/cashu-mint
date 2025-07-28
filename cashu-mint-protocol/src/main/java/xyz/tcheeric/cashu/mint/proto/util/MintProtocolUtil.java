package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

public class MintProtocolUtil {

    public static Gateway createGateway(@NonNull PaymentMethod method) {
        try {
            Gateway gateway = GatewayLoader.loadGateway();
            if(gateway.supports(method)) {
                return gateway;
            } else {
                throw new IllegalArgumentException("Gateway does not support payment method: " + method);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create gateway instance", e);
        }
    }

    public static PrivateKey getPrivateKey(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint) throws CashuErrorException {
        MintEntity mintEntity = toMintEntity(mint);
        DBMintVault mintVault = new DBMintVault(mintEntity);
        String unit = mintVault.getUnit(keySetId);
        if (unit != null) {
            return PrivateKey.fromString(mintVault.getPrivateKey(unit, amount));
        }
        return null;
    }

    public static String getCurrencyCode(@NonNull PaymentMethod paymentMethod) {
        return switch (paymentMethod) {
            case BOLT11, BOLT12, ON_CHAIN -> "BTC";
            default -> "USD";
        };
    }

    public static String createLightningAddressRequest(@NonNull String lnAddress, @NonNull Integer amount, String description) {
        try {
            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("lnAddress", lnAddress);
            requestMap.put("amount", amount);
            requestMap.put("description", description);

            ObjectMapper objectMapper = new ObjectMapper();
            String jsonString = objectMapper.writeValueAsString(requestMap);

            return Base64.getEncoder().encodeToString(jsonString.getBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Lightning address request", e);
        }
    }

    public static MintEntity toMintEntity(@NonNull Mint mint) {
        MintEntity mintEntity = new MintEntity();
        mintEntity.setId(UUID.fromString(mint.getId()));
        mint.getKeySets().forEach(keySet -> {
            KeySetEntity keySetEntity = toKeySetEntity(keySet);
            keySetEntity.setMint(mintEntity);
            mintEntity.getKeySets().add(keySetEntity);
        });
        return mintEntity;
    }

    public static KeySetEntity toKeySetEntity(@NonNull KeySet keySet) {
        KeySetEntity keySetEntity = new KeySetEntity();
        keySetEntity.setId(UUID.fromString(keySet.getId()));
        keySetEntity.setUnit(keySet.getUnit());
        return keySetEntity;
    }

    public static Keys toKeys(@NonNull Set<KeyEntity> keys) {
        Keys result = new Keys();
        for (KeyEntity keyEntity : keys) {
            result.put(keyEntity.getAmount(), PrivateKey.derivePublicKey(PrivateKey.fromString(keyEntity.getPrivateKey())));
        }
        return result;
    }

    public static <T extends Secret> ProofEntity toProofEntity(@NonNull Proof<T> proof, @NonNull MintEntity mintEntity) {
        ProofEntity proofEntity = new ProofEntity();
        proofEntity.setAmount(proof.getAmount());
        proofEntity.setSecret(proof.getSecret().toString());
        proofEntity.setWitness(proof.getWitness().toString());
        proofEntity.setUnblindedSignature(proof.getUnblindedSignature().toString());
        proofEntity.setMint(mintEntity);
        proofEntity.setState(ProofEntity.STATE_SPENT);
        return proofEntity;
    }

    public static String createRandomBytes(int length) {
        byte[] randomBytes = new byte[length];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(randomBytes);

        // Print the generated random bytes as a hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : randomBytes) {
            hexString.append(String.format("%02x", b));
        }

        return hexString.toString();
    }


    static class GatewayLoader {
        public static Gateway loadGateway() throws Exception {

            Properties properties = new Properties();

            try (FileInputStream input = new FileInputStream(GatewayLoader.class.getClassLoader().getResource("app.properties").getFile())) {
                properties.load(input);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load properties file", e);
            }

            String gatewayClassName = properties.getProperty("cashu.gateway");
            if (gatewayClassName == null || gatewayClassName.isEmpty()) {
                throw new IllegalArgumentException("Gateway class not specified in properties file");
            }

            Class<?> gatewayClass = Class.forName(gatewayClassName);
            Gateway gatewayInstance = (Gateway) gatewayClass.getDeclaredConstructor().newInstance();
            if (!(gatewayInstance instanceof Gateway)) {
                throw new IllegalArgumentException("Loaded class is not an instance of Gateway");
            }
            return gatewayInstance;
        }
    }

}
