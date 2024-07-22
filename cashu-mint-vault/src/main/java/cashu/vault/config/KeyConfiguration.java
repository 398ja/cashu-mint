package cashu.vault.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.math.BigInteger;

@AllArgsConstructor
@Builder
@Getter
public class KeyConfiguration implements EntityConfiguration {

    private final KeysetConfiguration keyset;
    private final BigInteger amount;
    private final String privateKey;

    public KeyConfiguration(@NonNull KeysetConfiguration keyset, @NonNull BigInteger amount) {
        this(keyset, amount, null);
    }

    public KeyConfiguration(@NonNull KeysetConfiguration keyset) {
        this(keyset, null, null);
    }
}
