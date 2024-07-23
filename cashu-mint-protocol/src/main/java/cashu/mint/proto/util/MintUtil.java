package cashu.mint.proto.util;

import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.PrivateKey;
import cashu.gateway.Gateway;
import cashu.gateway.mock.MockMeltGateway;
import cashu.gateway.mock.MockMintGateway;
import cashu.vault.config.MintConfiguration;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;

public class MintUtil {

    public static Gateway createGateway(@NonNull PaymentMethod method, String mockCode, @NonNull String operation) {
        return switch (method) {
            case MOCK -> {
                if (operation.equals("mint")) {
                    yield new MockMintGateway(mockCode);
                } else if (operation.equals("melt")) {
                    yield new MockMeltGateway(mockCode);
                } else {
                    throw new IllegalArgumentException("Invalid operation");
                }
            }
            default -> throw new IllegalArgumentException("Unknown payment method: " + method);
        };
    }

    public static Gateway createGateway(@NonNull PaymentMethod method, @NonNull String operation) {
        return createGateway(method, "mock", operation);
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


}
