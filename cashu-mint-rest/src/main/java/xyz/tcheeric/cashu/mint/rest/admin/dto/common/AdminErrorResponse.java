package xyz.tcheeric.cashu.mint.rest.admin.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Standard error payload returned by administrative endpoints.
 */
@Schema(description = "Standard error payload returned by administrative endpoints.")
public record AdminErrorResponse(
        @Schema(description = "HTTP status code of the error", example = "404") int status,
        @Schema(description = "Short human readable error", example = "Not Found") String error,
        @Schema(description = "Machine readable error code", example = "mint_not_found") String code,
        @Schema(description = "Detailed message about the failure", example = "Mint not found: mint-001") String message
) {
}
