package cashu.mint.admin.model.json;

import cashu.mint.admin.model.KeysDto;
import cashu.mint.admin.model.codec.KeysDecoder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class KeysDeserializer extends JsonDeserializer<KeysDto> {
    @Override
    public KeysDto deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node.isObject()) {
            KeysDecoder decoder = new KeysDecoder(node.toString());
            return decoder.decode();
        }
        throw new RuntimeException("Invalid KeysDto format");
    }
}
