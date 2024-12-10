package cashu.mint.proto.util;

import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.PrivateKey;
import cashu.gateway.Gateway;
import cashu.util.Configuration;
import cashu.vault.config.MintConfiguration;
import cashu.vault.impl.fs.FSMintVault;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class MintUtil {

    public static Gateway createGateway(@NonNull PaymentMethod method) {
        InputStream is = MintUtil.class.getResourceAsStream("/cashu.properties");
        Configuration configuration = Configuration.load(is);
        String gwClass = configuration.getValue("gateway");

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
    public static PrivateKey getPrivateKey(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint) {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        FSMintVault mintVault = new FSMintVault(mintConfiguration);
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
