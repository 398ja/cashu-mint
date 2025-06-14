package xyz.tcheeric.cashu.mint.admin.model.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import xyz.tcheeric.cashu.common.codec.Decoder;
import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;

@AllArgsConstructor
public class KeySetDecoder implements Decoder<KeySetDto> {

    private final String jsonString;

    @Override
    public KeySetDto decode() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(jsonString, KeySetDto.class);
    }
}
