package xyz.tcheeric.cashu.mint.admin.rest.dto.users;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * Representation of an operator account returned by the administrative API.
 */
@Schema(description = "Representation of an operator account returned by the administrative API.")
public record UserResponse(
        @Schema(description = "Identifier of the operator account", example = "alice") String userId,
        @Schema(description = "Display name of the operator", example = "Alice Operations") String displayName,
        @Schema(description = "Email associated with the operator", example = "alice@example.com") String email,
        @Schema(description = "Roles currently assigned to the operator", example = "[\"MINT_ADMIN\", \"USER_ADMIN\"]") Set<String> roles,
        @Schema(description = "Whether the operator account is active", example = "true") boolean active,
        @Schema(description = "Message describing the outcome of the request", example = "User updated") String message,
        @Schema(description = "Initial credential, present only on creation and shown once") String credential
) {

    /** Most responses carry no credential — only creation issues one. */
    public UserResponse(final String userId, final String displayName, final String email,
                        final Set<String> roles, final boolean active, final String message) {
        this(userId, displayName, email, roles, active, message, null);
    }
}
