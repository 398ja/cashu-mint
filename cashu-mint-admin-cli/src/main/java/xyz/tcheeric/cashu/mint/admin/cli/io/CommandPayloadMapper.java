package xyz.tcheeric.cashu.mint.admin.cli.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.Objects;

/**
 * Converts inline or file-based payloads into strongly typed command requests.
 */
public final class CommandPayloadMapper {

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public CommandPayloadMapper(final ObjectMapper jsonMapper, final ObjectMapper yamlMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
    }

    public static CommandPayloadMapper createDefault() {
        final ObjectMapper json = new ObjectMapper();
        json.findAndRegisterModules();
        final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        yaml.findAndRegisterModules();
        return new CommandPayloadMapper(json, yaml);
    }

    public ObjectMapper jsonMapper() {
        return jsonMapper;
    }

    public <T> T read(final String payload, final InputFormat format, final Class<T> type) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(type, "type");
        try {
            return selectMapper(format).readValue(payload, type);
        } catch (final JsonProcessingException ex) {
            throw new PayloadMappingException("Failed to parse payload for " + type.getSimpleName(), ex);
        }
    }

    private ObjectMapper selectMapper(final InputFormat format) {
        return switch (format) {
            case JSON -> jsonMapper;
            case YAML -> yamlMapper;
        };
    }
}
