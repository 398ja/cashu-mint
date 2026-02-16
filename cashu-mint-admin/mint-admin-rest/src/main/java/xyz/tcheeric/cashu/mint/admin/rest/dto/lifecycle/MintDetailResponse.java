package xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Detailed mint view for list and detail endpoints.
 */
@Schema(description = "Mint instance details.")
public record MintDetailResponse(
        @Schema(description = "Mint identifier") String mintId,
        @Schema(description = "Current lifecycle state") String lifecycleState,
        @Schema(description = "Current configuration revision ID") long configurationRevisionId,
        @Schema(description = "Configuration parameters") Map<String, String> configurationParameters,
        @Schema(description = "Version tag") String versionTag,
        @Schema(description = "Last actor who modified the mint") String lastActor,
        @Schema(description = "Last action performed") String lastAction,
        @Schema(description = "Timestamp of last modification") Instant lastModified
) {
}
