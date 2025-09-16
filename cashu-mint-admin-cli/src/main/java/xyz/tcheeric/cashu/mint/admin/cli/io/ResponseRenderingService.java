package xyz.tcheeric.cashu.mint.admin.cli.io;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry of response renderers keyed by output format.
 */
public final class ResponseRenderingService {

    private final Map<OutputFormat, ResponseRenderer> renderers;

    public ResponseRenderingService(final Collection<ResponseRenderer> renderers) {
        final Map<OutputFormat, ResponseRenderer> lookup = new EnumMap<>(OutputFormat.class);
        for (final ResponseRenderer renderer : renderers) {
            lookup.put(renderer.format(), renderer);
        }
        this.renderers = Map.copyOf(lookup);
    }

    public static ResponseRenderingService createDefault(final ObjectMapper jsonMapper) {
        Objects.requireNonNull(jsonMapper, "jsonMapper");
        return new ResponseRenderingService(
            java.util.List.of(
                new JsonResponseRenderer(jsonMapper),
                new TableResponseRenderer(jsonMapper)
            )
        );
    }

    public String render(final Object response, final OutputFormat format) {
        Objects.requireNonNull(format, "format");
        if (response == null) {
            return "";
        }
        final ResponseRenderer renderer = renderers.get(format);
        if (renderer == null) {
            throw new ResponseRenderingException("No renderer registered for format " + format);
        }
        return renderer.render(response);
    }
}
