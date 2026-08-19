package xyz.tcheeric.cashu.mint.admin.rest.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Aggregated dashboard summary for the admin web interface.
 */
@Schema(description = "Dashboard summary with aggregated counts.")
public record DashboardSummaryResponse(
        @Schema(description = "Mint counts by lifecycle state") Map<String, Long> mintsByState,
        @Schema(description = "Number of active operational controls") long activeControls
) {
}
