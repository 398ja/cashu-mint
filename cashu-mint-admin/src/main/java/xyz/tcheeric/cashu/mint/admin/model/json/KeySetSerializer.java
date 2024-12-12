package xyz.tcheeric.cashu.mint.admin.model.json;

import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class KeySetSerializer extends JsonSerializer<KeySetDto> {
    @Override
    public void serialize(KeySetDto value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("id", value.getId());
        gen.writeStringField("unit", value.getUnit());
        gen.writeObjectFieldStart("keys");
        gen.writeObject(value.getKeys());
        gen.writeEndObject();
    }
}
