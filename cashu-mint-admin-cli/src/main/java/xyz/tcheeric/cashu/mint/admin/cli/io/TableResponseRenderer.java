package xyz.tcheeric.cashu.mint.admin.cli.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders responses as simple ASCII tables.
 */
public final class TableResponseRenderer implements ResponseRenderer {

    private final ObjectMapper mapper;

    public TableResponseRenderer(final ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    }

    @Override
    public OutputFormat format() {
        return OutputFormat.TABLE;
    }

    @Override
    public String render(final Object response) {
        if (response == null) {
            return "";
        }
        final JsonNode node = mapper.valueToTree(response);
        final List<Map<String, String>> rows = extractRows(node);
        if (rows.isEmpty()) {
            return "(no data)";
        }
        final List<String> headers = new ArrayList<>(collectHeaders(rows));
        final Map<String, Integer> widths = computeColumnWidths(headers, rows);
        final String border = buildBorder(widths);
        final StringBuilder builder = new StringBuilder();
        builder.append(border).append(System.lineSeparator());
        builder.append(buildHeaderRow(headers, widths)).append(System.lineSeparator());
        builder.append(border).append(System.lineSeparator());
        for (final Map<String, String> row : rows) {
            builder.append(buildRow(headers, row, widths)).append(System.lineSeparator());
        }
        builder.append(border);
        return builder.toString();
    }

    private List<Map<String, String>> extractRows(final JsonNode node) {
        final List<Map<String, String>> rows = new ArrayList<>();
        if (node == null || node.isNull()) {
            return rows;
        }
        if (node.isArray()) {
            for (final JsonNode child : node) {
                rows.add(asRow(child));
            }
        } else {
            rows.add(asRow(node));
        }
        return rows;
    }

    private Map<String, String> asRow(final JsonNode node) {
        final Map<String, String> row = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> row.put(entry.getKey(), toCellValue(entry.getValue())));
        } else {
            row.put("value", toCellValue(node));
        }
        return row;
    }

    private String toCellValue(final JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        return node.toString();
    }

    private Set<String> collectHeaders(final List<Map<String, String>> rows) {
        final Set<String> headers = new LinkedHashSet<>();
        for (final Map<String, String> row : rows) {
            headers.addAll(row.keySet());
        }
        return headers;
    }

    private Map<String, Integer> computeColumnWidths(final List<String> headers,
                                                      final List<Map<String, String>> rows) {
        final Map<String, Integer> widths = new LinkedHashMap<>();
        for (final String header : headers) {
            widths.put(header, header.length());
        }
        for (final Map<String, String> row : rows) {
            for (final String header : headers) {
                final String value = row.getOrDefault(header, "");
                widths.compute(header, (key, width) -> Math.max(width, value.length()));
            }
        }
        return widths;
    }

    private String buildBorder(final Map<String, Integer> widths) {
        final StringBuilder border = new StringBuilder();
        border.append('+');
        for (final int width : widths.values()) {
            border.append("-".repeat(width + 2)).append('+');
        }
        return border.toString();
    }

    private String buildHeaderRow(final List<String> headers, final Map<String, Integer> widths) {
        final Map<String, String> headerRow = new LinkedHashMap<>();
        for (final String header : headers) {
            headerRow.put(header, header);
        }
        return buildRow(headers, headerRow, widths);
    }

    private String buildRow(final List<String> headers,
                            final Map<String, String> row,
                            final Map<String, Integer> widths) {
        final StringBuilder builder = new StringBuilder();
        builder.append('|');
        for (final String header : headers) {
            final String value = row.getOrDefault(header, "");
            builder.append(' ').append(padRight(value, widths.get(header))).append(' ').append('|');
        }
        return builder.toString();
    }

    private String padRight(final String value, final int width) {
        final int padding = Math.max(0, width - value.length());
        return value + " ".repeat(padding);
    }
}
