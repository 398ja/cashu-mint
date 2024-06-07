package cashu.mint.util;

import cashu.common.model.PaymentMethod;
import cashu.mint.gateway.Gateway;
import cashu.mint.gateway.mock.MockGateway;
import lombok.NonNull;

public class MintUtil {

    public static Gateway createGateway(@NonNull PaymentMethod method) {
        return switch (method) {
            case MOCK -> new MockGateway();
            default -> throw new IllegalArgumentException("Unknown payment method: " + method);
        };
    }

}
