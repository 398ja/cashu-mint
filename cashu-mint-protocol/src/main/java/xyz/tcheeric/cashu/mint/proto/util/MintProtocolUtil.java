package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.vault.api.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.common.util.Configuration;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class MintProtocolUtil {

    public static Gateway createGateway(@NonNull PaymentMethod method) {
        Configuration configuration = new Configuration("cashu");
        String gwClass = configuration.get("gateway");

        try {
            Class<?> clazz = Class.forName(gwClass);
            Gateway gateway = (Gateway) clazz.getDeclaredConstructor().newInstance();
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
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        DBMintVault mintVault = new DBMintVault(mintConfiguration);
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
}
