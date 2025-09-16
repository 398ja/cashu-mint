package xyz.tcheeric.cashu.mint.admin.rest.dto.configuration;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Response payload describing configuration mutations or previews.
 */
@Schema(description = "Result of applying or previewing configuration changes for a mint.")
public record ConfigurationActionResponse(
        @Schema(description = "Identifier of the mint the configuration belongs to", example = "mint-001") String mintId,
        @Schema(description = "Revision identifier representing the configuration snapshot", example = "rev-1") String revisionId,
        @Schema(description = "Flattened configuration parameters", example = "{\"currency\":\"sat\"}") Map<String, String> parameters,
        @Schema(description = "Message explaining the effect of the operation", example = "Configuration applied") String message
) {
}
