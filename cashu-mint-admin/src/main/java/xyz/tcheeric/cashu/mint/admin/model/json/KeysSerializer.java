package xyz.tcheeric.cashu.mint.admin.model.json;

import xyz.tcheeric.cashu.common.model.PrivateKey;
import xyz.tcheeric.cashu.mint.admin.model.KeysDto;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;

public class KeysSerializer extends JsonSerializer<KeysDto> {
    @Override
    public void serialize(KeysDto value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        for (Map.Entry<BigInteger, PrivateKey> entry : value.getValues().entrySet()) {
            gen.writeStringField(String.valueOf(entry.getKey()), entry.getValue().toString());
        }
        gen.writeEndObject();
    }
}