package xyz.tcheeric.cashu.mint.admin.model;

import xyz.tcheeric.cashu.common.model.Keys;
import xyz.tcheeric.cashu.common.model.PrivateKey;
import xyz.tcheeric.cashu.common.model.PublicKey;
import xyz.tcheeric.cashu.mint.admin.model.json.KeysDeserializer;
import xyz.tcheeric.cashu.mint.admin.model.json.KeysSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

@Getter
@JsonDeserialize(using = KeysDeserializer.class)
@JsonSerialize(using = KeysSerializer.class)
@AllArgsConstructor
public class KeysDto {

    private final Map<BigInteger, PrivateKey> values = new HashMap<>();

    public KeysDto put(BigInteger key, PrivateKey value) {
        values.put(key, value);
        return this;
    }

    public PrivateKey get(int key) {
        return values.get(BigInteger.valueOf(key));
    }

    public static Keys toKeys(KeysDto keysDto) {
        Keys keys = new Keys();
        for (Map.Entry<BigInteger, PrivateKey> entry : keysDto.getValues().entrySet()) {
            keys.put(entry.getKey(), PrivateKey.derivePublicKey(entry.getValue()));
        }
        return keys;
    }

    public Map<BigInteger, byte[]> values() {
        Map<BigInteger, byte[]> keys = new HashMap<>();
        for (Map.Entry<BigInteger, PrivateKey> entry : values.entrySet()) {
            keys.put(entry.getKey(), entry.getValue().toBytes());
        }
        return keys;
    }
}
