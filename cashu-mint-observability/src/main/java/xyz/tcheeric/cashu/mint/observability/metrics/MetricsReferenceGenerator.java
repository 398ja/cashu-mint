package xyz.tcheeric.cashu.mint.observability.metrics;

import java.util.List;

/**
 * Renders the recorder-declared portion of {@code docs/metrics-reference.md}
 * — issue #348.
 *
 * <p>The reference used to be written by hand, which is why it documented
 * roughly fifty metrics of which most were never emitted: it was a third
 * artefact with nothing tying it to the code. Now that every name is declared
 * in exactly one place, the document is derived rather than written, and
 * {@code MetricsReferenceContractTest} fails the build when the checked-in
 * copy no longer matches what the recorders declare.
 */
public final class MetricsReferenceGenerator {

    /** Marks the start of the generated block in the reference document. */
    public static final String BEGIN_MARKER = "<!-- BEGIN GENERATED METRICS -- do not edit by hand, see MetricsReferenceGenerator -->";

    /** Marks the end of the generated block. */
    public static final String END_MARKER = "<!-- END GENERATED METRICS -->";

    private MetricsReferenceGenerator() {
    }

    /**
     * @return the markdown block, markers included, ending with a newline
     */
    public static String render() {
        StringBuilder out = new StringBuilder(BEGIN_MARKER).append("\n\n");
        out.append("| Metric | Type | Labels | Description |\n");
        out.append("|--------|------|--------|-------------|\n");
        List<MetricCatalogue.Declaration> declarations = MetricCatalogue.declared();
        for (MetricCatalogue.Declaration d : declarations) {
            out.append("| `").append(d.name()).append("` | ")
                    .append(d.type()).append(" | ")
                    .append(d.tags()).append(" | ")
                    .append(d.description()).append(" |\n");
        }
        out.append("\n").append(END_MARKER).append("\n");
        return out.toString();
    }

    /**
     * Splices {@link #render()} into {@code document} between the markers.
     *
     * @param document the current reference document
     * @return the document with the generated block replaced
     * @throws IllegalArgumentException if the markers are missing or reversed
     */
    public static String splice(String document) {
        int begin = document.indexOf(BEGIN_MARKER);
        int end = document.indexOf(END_MARKER);
        if (begin < 0 || end < 0 || end < begin) {
            throw new IllegalArgumentException(
                    "metrics reference is missing the generated-block markers");
        }
        return document.substring(0, begin) + render() + document.substring(end + END_MARKER.length() + 1);
    }
}
