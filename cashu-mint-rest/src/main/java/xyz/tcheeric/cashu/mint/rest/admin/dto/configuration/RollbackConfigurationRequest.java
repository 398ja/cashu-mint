package xyz.tcheeric.cashu.mint.rest.admin.dto.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;

/**
 * Request payload for rolling back configuration to a previous revision.
 */
public record RollbackConfigurationRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotBlank(message = "target revision id is required") String targetRevisionId,
        @NotBlank(message = "reason is required") String reason
) {
}
