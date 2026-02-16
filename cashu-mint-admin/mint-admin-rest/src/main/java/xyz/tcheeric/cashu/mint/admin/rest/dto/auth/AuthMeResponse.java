package xyz.tcheeric.cashu.mint.admin.rest.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * Response payload for the auth/me endpoint returning caller identity.
 */
@Schema(description = "Caller identity and role information.")
public record AuthMeResponse(
        @Schema(description = "Whether the token is valid") boolean authenticated,
        @Schema(description = "Roles associated with the caller") Set<String> roles
) {
}
