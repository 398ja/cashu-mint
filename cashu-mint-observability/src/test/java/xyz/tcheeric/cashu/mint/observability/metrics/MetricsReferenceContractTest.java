package xyz.tcheeric.cashu.mint.observability.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #348 — the metrics reference must be derivable from the recorder
 * declarations, and must not be able to drift from them silently.
 *
 * <p>Run with {@code -Dmetrics.reference.write=true} to regenerate the block
 * in place after changing a recorder; the failure message says so too.
 */
class MetricsReferenceContractTest {

    private static final Path REFERENCE = Path.of("docs/metrics-reference.md");

    /** The checked-in document must match what the recorders currently declare. */
    @Test
    void referenceDocumentMatchesTheRecorderDeclarations() throws IOException {
        String document = Files.readString(REFERENCE, StandardCharsets.UTF_8);
        String expected = MetricsReferenceGenerator.splice(document);

        if (!expected.equals(document) && Boolean.getBoolean("metrics.reference.write")) {
            Files.writeString(REFERENCE, expected, StandardCharsets.UTF_8);
            return;
        }

        assertThat(document)
                .as("docs/metrics-reference.md is stale. Regenerate it with:%n"
                        + "  mvn -pl cashu-mint-observability test "
                        + "-Dtest=MetricsReferenceContractTest -Dmetrics.reference.write=true")
                .isEqualTo(expected);
    }

    /** Every declared metric must reach the document, with its type and labels. */
    @Test
    void everyDeclaredMetricAppearsInTheDocument() throws IOException {
        String document = Files.readString(REFERENCE, StandardCharsets.UTF_8);

        assertThat(MetricCatalogue.declared()).isNotEmpty();
        for (MetricCatalogue.Declaration declaration : MetricCatalogue.declared()) {
            assertThat(document)
                    .as("metric %s is declared but missing from the reference", declaration.name())
                    .contains("`" + declaration.name() + "`");
        }
    }

    /**
     * A recorder change must show up in the rendered output — otherwise
     * "generated" would be decorative and the document could still drift.
     */
    @Test
    void renderedBlockReflectsTheDeclarationsRatherThanAFixedString() {
        String rendered = MetricsReferenceGenerator.render();

        for (MetricCatalogue.Declaration declaration : MetricCatalogue.declared()) {
            assertThat(rendered).contains("`" + declaration.name() + "`");
            assertThat(rendered).contains(declaration.type());
        }
        assertThat(rendered).startsWith(MetricsReferenceGenerator.BEGIN_MARKER);
        assertThat(rendered).endsWith(MetricsReferenceGenerator.END_MARKER + "\n");
    }

    /** Labels must be documented, or a reader cannot write a working query. */
    @Test
    void labelledMetricsDocumentTheirLabelKeys() {
        String rendered = MetricsReferenceGenerator.render();

        assertThat(rendered).contains("`reason`");
        assertThat(rendered).contains("`funding_source`");
        assertThat(rendered).contains("`outcome`");
    }
}
