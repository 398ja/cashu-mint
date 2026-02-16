package xyz.tcheeric.cashu.mint.admin.rest.dto.audit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Single audit event for the audit timeline.
 */
@Schema(description = "An audit event from the admin audit trail.")
public record AuditEventResponse(
        @Schema(description = "Mint identifier") String mintId,
        @Schema(description = "Sequence number within the mint's audit trail") long sequence,
        @Schema(description = "Actor who performed the action") String actor,
        @Schema(description = "Action performed") String action,
        @Schema(description = "Timestamp of the event") Instant timestamp,
        @Schema(description = "Configuration revision at time of event", nullable = true) Long configurationRevisionId
) {
}
