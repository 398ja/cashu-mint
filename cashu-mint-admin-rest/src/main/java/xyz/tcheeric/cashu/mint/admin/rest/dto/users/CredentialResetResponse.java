package xyz.tcheeric.cashu.mint.admin.rest.dto.users;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response returned when a credential reset workflow is initiated.
 */
@Schema(description = "Response returned when a credential reset workflow is initiated.")
public record CredentialResetResponse(
        @Schema(description = "Identifier of the operator account", example = "alice") String userId,
        @Schema(description = "Token to drive the credential reset flow", example = "alice-reset-1") String resetToken,
        @Schema(description = "Message describing the reset outcome", example = "Reset token issued") String message
) {
}
