package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The set of metrics the mint declares, derived from the recorder
 * implementations themselves — issue #348.
 *
 * <p>There is no hand-maintained list here on purpose. Every recorder
 * registers its meters eagerly in its constructor (so no family first appears
 * on failure), which means instantiating them against a throwaway registry
 * <em>is</em> the declaration. A metric can therefore only enter the catalogue
 * by being declared on a recorder, and the reference document and the
 * catalogue contract test (#347) both read it from here rather than from a
 * third artefact that can drift.
 *
 * <p>Dynamically-named families — HTTP request metrics, per-task timers,
 * per-lock gauges — are not in scope: their names are composed at runtime from
 * request and task identity, so there is nothing to declare up front.
 */
public final class MetricCatalogue {

    /**
     * One declared metric series.
     *
     * @param name        the exposition name
     * @param type        Micrometer meter type, lower-cased
     * @param tags        label keys carried by the series, comma-separated, or "—"
     * @param description the meter description
     */
    public record Declaration(String name, String type, String tags, String description) {
    }

    private MetricCatalogue() {
    }

    /**
     * Instantiates every recorder against a throwaway registry and reads back
     * what they declared.
     *
     * @return declarations, de-duplicated by name and sorted
     */
    public static List<Declaration> declared() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MicrometerMeltMetricsRecorder(registry);
        new MicrometerVoucherMetricsRecorder(registry);
        new MicrometerIssuanceMetricsRecorder(registry);
        new MicrometerWebhookMetricsRecorder(registry);

        // Gauges declare themselves when bound, so bind them to a constant.
        MicrometerInvariantMetricsRecorder invariant = new MicrometerInvariantMetricsRecorder(registry);
        invariant.bindStuckPaymentUnknown(() -> 0);
        invariant.bindPaymentSentBurnFailed(() -> 0);
        invariant.bindOrphanIssuance(() -> 0);

        return registry.getMeters().stream()
                .map(MetricCatalogue::toDeclaration)
                .collect(Collectors.toMap(Declaration::name, d -> d, (first, second) -> first))
                .values().stream()
                .sorted(Comparator.comparing(Declaration::name))
                .toList();
    }

    /**
     * @return just the declared metric names
     */
    public static List<String> declaredNames() {
        return declared().stream().map(Declaration::name).toList();
    }

    private static Declaration toDeclaration(Meter meter) {
        Meter.Id id = meter.getId();
        String tags = id.getTags().stream()
                .map(Tag::getKey)
                .sorted()
                .map(key -> "`" + key + "`")
                .collect(Collectors.joining(", "));
        return new Declaration(
                id.getName(),
                id.getType().name().toLowerCase(java.util.Locale.ROOT),
                tags.isEmpty() ? "—" : tags,
                id.getDescription() == null ? "" : id.getDescription());
    }
}
