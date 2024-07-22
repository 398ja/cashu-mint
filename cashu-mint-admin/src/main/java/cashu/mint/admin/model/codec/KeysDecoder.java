package cashu.mint.admin.model.codec;

import cashu.common.json.codec.Decoder;
import cashu.common.model.PrivateKey;
import cashu.mint.admin.model.KeysDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;

import java.math.BigInteger;
import java.util.Map;

@AllArgsConstructor
public class KeysDecoder implements Decoder<KeysDto> {

    private final String jsonString;

    @Override
    public KeysDto decode() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, PrivateKey> map = objectMapper.readValue(jsonString, new TypeReference<>() {});
        KeysDto keysDto = new KeysDto();
        for (Map.Entry<String, PrivateKey> entry : map.entrySet()) {
            var privateKey = entry.getValue();
            keysDto.put(new BigInteger(entry.getKey()), privateKey);
        }
        return keysDto;
    }
}
