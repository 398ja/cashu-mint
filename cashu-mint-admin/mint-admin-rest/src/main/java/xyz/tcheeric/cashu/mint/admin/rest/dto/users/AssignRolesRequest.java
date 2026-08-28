package xyz.tcheeric.cashu.mint.admin.rest.dto.users;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * Request payload for assigning roles to an operator account.
 */
public record AssignRolesRequest(
        @NotEmpty(message = "at least one role must be provided") Set<@NotBlank(message = "role must not be blank") String> roles,
        String justification
) {
}
