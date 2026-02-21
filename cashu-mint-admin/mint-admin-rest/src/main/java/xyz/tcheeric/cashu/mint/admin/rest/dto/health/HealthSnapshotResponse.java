package xyz.tcheeric.cashu.mint.admin.rest.dto.health;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response payload representing a mint health snapshot.
 */
@Schema(description = "Health snapshot for a mint instance.")
public record HealthSnapshotResponse(
        @Schema(description = "Identifier of the mint", example = "123e4567-e89b-12d3-a456-426614174000") String mintId,
        @Schema(description = "Health status", example = "HEALTHY") String status,
        @Schema(description = "Current lifecycle state", example = "ACTIVE") String lifecycleState,
        @Schema(description = "Timestamp of the health check") Instant checkedAt,
        @Schema(description = "Message describing the result", example = "Health snapshot retrieved") String message
) {
}
