package xyz.tcheeric.cashu.mint.admin.cli.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Renders responses using pretty-printed JSON.
 */
public final class JsonResponseRenderer implements ResponseRenderer {

    private final ObjectMapper objectMapper;

    public JsonResponseRenderer(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    @Override
    public OutputFormat format() {
        return OutputFormat.JSON;
    }

    @Override
    public String render(final Object response) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
        } catch (final JsonProcessingException ex) {
            throw new ResponseRenderingException("Failed to render response as JSON", ex);
        }
    }
}
