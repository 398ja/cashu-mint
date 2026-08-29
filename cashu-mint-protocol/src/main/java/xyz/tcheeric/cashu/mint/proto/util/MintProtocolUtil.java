package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.api.KeyVault;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Protocol utilities for the Cashu mint.
 *
 * <p><b>Security:</b> This class handles gateway instantiation and key lookups.
 * Private keys are retrieved only from authenticated vault services.
 */
public final class MintProtocolUtil {

    private static final Logger log = LoggerFactory.getLogger(MintProtocolUtil.class);

    private MintProtocolUtil() {
        // Utility class - prevent instantiation
    }

    public static Gateway createGateway(@NonNull PaymentMethod method) {
        return createGateway(method, null);
    }

    public static Gateway createGateway(@NonNull PaymentMethod method, String unit) {
        try {
            Gateway gateway = GatewayLoader.loadGateway(method, unit);
            if (!gateway.supports(method)) {
                log.error("Gateway does not support payment method: {}", method);
                throw new IllegalArgumentException("Gateway configuration error");
            }
            return gateway;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create gateway instance", e);
        }
    }

    /**
     * Resolve the signing key for a new output, refusing an archived keyset.
     *
     * <p>Shares the one keyset lookup {@link #getPrivateKey} already performs
     * rather than adding a second: a separate check would make issuance depend on
     * two independent vault calls succeeding, and turn any hiccup on the second
     * into a failed mint.
     *
     * <p>Archived means retired for issuance only. Proofs the keyset already
     * signed must still verify, swap and melt indefinitely, which is why
     * {@link #getPrivateKey} — used by the redemption paths — carries no such
     * check. See ADR-0004.
     *
     * @param keySetId external Cashu keyset id the client asked to be signed against
     * @param amount denomination to sign
     * @param mint the mint
     * @return the signing key
     * @throws CashuErrorException {@code keyset_inactive} when the keyset is archived
     */
    public static PrivateKey getPrivateKeyForSigning(@NonNull String keySetId, @NonNull Integer amount,
                                                     @NonNull Mint mint) throws CashuErrorException {
        KeySetEntity keySet = requireKeySet(keySetId);
        if (keySet.isArchived()) {
            log.warn("Refusing to sign with archived keyset: keySetId={}", keySetId);
            throw new CashuErrorException(CashuErrorCode.keyset_inactive,
                    "Keyset " + keySetId + " is archived and no longer signs. "
                            + "Re-read /v1/keys and retry against an active keyset.");
        }
        return retrieveKey(keySet, amount);
    }

    private static KeySetEntity requireKeySet(@NonNull String keySetId) throws CashuErrorException {
        KeySetVaultClient keySetClient = VaultClientFactory.keySetClient();
        KeySetEntity keySet = keySetClient.getByKeySetId(keySetId);
        if (keySet == null || keySet.getId() == null) {
            throw new CashuErrorException(CashuErrorCode.keyset_not_found,
                    "Keyset " + keySetId + " is not known to this mint.");
        }
        return keySet;
    }

    private static PrivateKey retrieveKey(@NonNull KeySetEntity keySet, @NonNull Integer amount)
            throws CashuErrorException {
        KeyVault keyVault = VaultClientFactory.keyVault();
        KeyEntity keyEntity = keyVault.retrieveByAmount(java.math.BigInteger.valueOf(amount),
                keySet.getId().toString());
        return PrivateKey.fromString(keyEntity.getPrivateKey());
    }

    public static PrivateKey getPrivateKey(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint) throws CashuErrorException {
        // keySetId here is the external Cashu keyset id (16-char hex), not the DB UUID.
        // Resolve the internal KeySet UUID first, then fetch keys by that internal id.
        if (log.isDebugEnabled()) {
            log.debug("Resolve private key: externalKeySetId={} amount={}", keySetId, amount);
        }
        xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient ksc = xyz.tcheeric.cashu.vault.api.VaultClientFactory.keySetClient();
        xyz.tcheeric.cashu.vault.db.model.KeySetEntity kse = ksc.getByKeySetId(keySetId);
        if (kse == null || kse.getId() == null) {
            throw new CashuErrorException(CashuErrorCode.keyset_not_found);
        }
        // Route through VaultClientFactory.keyVault() so the active backend
        // (HASHICORP on staging/prod, DB in tests) is honoured. Directly
        // instantiating DBKeyVault bypasses HCKeyVault.enrichWithVaultSecret
        // and leaves KeyEntity.privateKey null even when Hashi has the key —
        // the silent mint-signing failure pattern observed on 2026-05-24.
        xyz.tcheeric.cashu.vault.api.KeyVault keyVault = xyz.tcheeric.cashu.vault.api.VaultClientFactory.keyVault();
        xyz.tcheeric.cashu.vault.db.model.KeyEntity keyEntity = keyVault.retrieveByAmount(java.math.BigInteger.valueOf(amount), kse.getId().toString());
        PrivateKey pk = PrivateKey.fromString(keyEntity.getPrivateKey());
        if (log.isDebugEnabled()) {
            log.debug("Resolved private key for amount={} internalKeySetUUID={}", amount, kse.getId());
        }
        return pk;
    }

    public static String getCurrencyCode(@NonNull PaymentMethod paymentMethod) {
        return switch (paymentMethod) {
            case BOLT11, BOLT12, ON_CHAIN -> "BTC";
            default -> "USD";
        };
    }

    public static String createLightningAddressRequest(@NonNull String lnAddress, @NonNull Integer amount, String description) {
        try {
            Map<String, Object> requestMap = new HashMap<>(3);  // 3 known entries
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
        // External key set id is stored in dedicated field; DB primary key remains a UUID generated by JPA base class
        keySetEntity.setKeySetId(keySet.getId());
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
        // Store Y coordinate (hash_to_curve result) not raw secret string
        // This ensures consistent length (66 hex chars) regardless of secret type (RSS, P2PK, VOUCHER, etc.)
        proofEntity.setSecret(proof.getSecret() != null ? SecretUtil.toY(proof.getSecret()) : null);
        // Witness is optional for RSS proofs; persist only when present
        if (proof.getWitness() != null) {
            proofEntity.setWitness(proof.getWitness().toString());
        } else {
            proofEntity.setWitness(null);
        }
        // Unblinded signature should be present for spent proofs, but be defensive
        proofEntity.setUnblindedSignature(proof.getUnblindedSignature() != null
                ? proof.getUnblindedSignature().toString()
                : null);
        proofEntity.setMint(mintEntity);
        proofEntity.setState(ProofEntity.STATE_SPENT);
        return proofEntity;
    }

    public static String createRandomBytes(int length) {
        byte[] randomBytes = new byte[length];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(randomBytes);

        // Print the generated random bytes as a hex string
        StringBuilder hexString = new StringBuilder(length * 2);  // Each byte becomes 2 hex chars
        for (byte b : randomBytes) {
            hexString.append(String.format("%02x", b));
        }

        return hexString.toString();
    }


    static class GatewayLoader {
        public static Gateway loadGateway(@NonNull PaymentMethod method, String unit) throws Exception {
            Properties properties = new Properties();
            try (InputStream input = GatewayLoader.class.getClassLoader().getResourceAsStream("proto.properties")) {
                if (input == null) {
                    throw new RuntimeException("Failed to load properties file");
                }
                properties.load(input);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load properties file", e);
            }

            String methodKey = method.name().toLowerCase();

            // 1) Environment override has highest precedence
            String gatewayClassName = null;
            if (unit != null && !unit.isBlank()) {
                String envWithUnit = ("GATEWAY_" + methodKey + "_" + unit).toUpperCase();
                gatewayClassName = System.getenv(envWithUnit);
            }
            if (gatewayClassName == null || gatewayClassName.isBlank()) {
                String envGeneric = ("GATEWAY_" + methodKey).toUpperCase();
                gatewayClassName = System.getenv(envGeneric);
            }
            // 2) Fall back to properties
            if (gatewayClassName == null || gatewayClassName.isBlank()) {
                if (unit != null && !unit.isBlank()) {
                    String keyWithUnit = "gateway." + methodKey + "." + unit.toLowerCase();
                    gatewayClassName = properties.getProperty(keyWithUnit);
                }
            }
            if (gatewayClassName == null || gatewayClassName.isBlank()) {
                String key = "gateway." + methodKey;
                gatewayClassName = properties.getProperty(key);
            }
            if (gatewayClassName == null || gatewayClassName.isBlank()) {
                log.error("Gateway class not specified for method {} and unit {}", method, unit);
                throw new IllegalArgumentException("Gateway not configured");
            }

            Class<?> gatewayClass = Class.forName(gatewayClassName);
            if (!Gateway.class.isAssignableFrom(gatewayClass)) {
                log.error("Configured gateway does not implement Gateway interface: {}", gatewayClassName);
                throw new IllegalArgumentException("Invalid gateway configuration");
            }
            return (Gateway) gatewayClass.getDeclaredConstructor().newInstance();
        }
    }

}
