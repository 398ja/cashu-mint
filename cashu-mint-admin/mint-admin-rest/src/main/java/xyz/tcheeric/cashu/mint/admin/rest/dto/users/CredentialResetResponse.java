package xyz.tcheeric.cashu.mint.admin.rest.dto.users;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response returned when a credential reset workflow is initiated.
 */
@Schema(description = "Response returned when a credential reset workflow is initiated.")
public record CredentialResetResponse(
        @Schema(description = "Identifier of the operator account", example = "alice") String userId,
        @Schema(description = "Newly issued credential, shown once and never stored in plaintext") String resetToken,
        @Schema(description = "Message describing the reset outcome", example = "Credential issued") String message
) {
}
