package cashu.mint.admin.model.codec;

import cashu.common.json.codec.Decoder;
import cashu.mint.admin.model.KeySetDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class KeySetDecoder implements Decoder<KeySetDto> {

    private final String jsonString;

    @Override
    public KeySetDto decode() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(jsonString, KeySetDto.class);
    }
}
