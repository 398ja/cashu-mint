package xyz.tcheeric.cashu.mint.admin.rest.dto.configuration;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Configuration revision entry for revision history.
 */
@Schema(description = "A configuration revision snapshot.")
public record ConfigurationRevisionResponse(
        @Schema(description = "Revision identifier") long revisionId,
        @Schema(description = "Version tag") String versionTag,
        @Schema(description = "Configuration parameters") Map<String, String> parameters
) {
}
