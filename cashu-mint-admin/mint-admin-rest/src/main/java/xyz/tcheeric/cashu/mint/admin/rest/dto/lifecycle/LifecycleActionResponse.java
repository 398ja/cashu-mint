package xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response payload summarising the result of a lifecycle action.
 */
@Schema(description = "Result of an administrative lifecycle action mirrored from the CLI workflows.")
public record LifecycleActionResponse(
        @Schema(description = "Lifecycle operation that was attempted", example = "CREATE") String operation,
        @Schema(description = "Identifier of the affected mint", example = "mint-001") String mintId,
        @Schema(description = "Lifecycle state before the operation", example = "PROVISIONED", nullable = true) String previousState,
        @Schema(description = "Lifecycle state after the operation", example = "SUSPENDED") String currentState,
        @Schema(description = "Version tag associated with the lifecycle change", example = "2024-Q1") String versionTag,
        @Schema(description = "Whether the operation mutated state", example = "true") boolean changed,
        @Schema(description = "Additional detail about the outcome", example = "Mint paused") String message
) {
}
