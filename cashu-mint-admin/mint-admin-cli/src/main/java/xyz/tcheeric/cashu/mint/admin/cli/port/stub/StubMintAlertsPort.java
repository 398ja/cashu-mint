package xyz.tcheeric.cashu.mint.admin.cli.port.stub;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertsRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintAlertsPort;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Stub implementation returning synthetic alert records.
 */
public final class StubMintAlertsPort implements MintAlertsPort {

    private static final List<MintAlertRecord> ALERTS = List.of(
        new MintAlertRecord("alert-1", "INFO", "Scheduled maintenance window", OffsetDateTime.now().minusHours(12).toString()),
        new MintAlertRecord("alert-2", "WARN", "Pending rate limit breach", OffsetDateTime.now().minusHours(2).toString()),
        new MintAlertRecord("alert-3", "ERROR", "Lightning backend unreachable", OffsetDateTime.now().minusMinutes(15).toString())
    );

    @Override
    public List<MintAlertRecord> listAlerts(final MintAlertsRequest request) {
        final String severity = request.severity().toUpperCase(Locale.ROOT);
        return ALERTS.stream()
            .filter(alert -> alertSeverityAtLeast(alert.severity(), severity))
            .collect(Collectors.toList());
    }

    private boolean alertSeverityAtLeast(final String candidate, final String threshold) {
        final List<String> ordering = List.of("INFO", "WARN", "ERROR");
        final int candidateIndex = ordering.indexOf(candidate.toUpperCase(Locale.ROOT));
        final int thresholdIndex = ordering.indexOf(threshold.toUpperCase(Locale.ROOT));
        return candidateIndex >= thresholdIndex && candidateIndex >= 0;
    }
}
