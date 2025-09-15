package xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;

/**
 * Request payload for provisioning a new mint instance.
 */
public record CreateMintRequest(
        @NotBlank(message = "mint id is required") String mintId,
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotNull(message = "metadata is required") @Valid MintMetadataDto metadata,
        @NotNull(message = "configuration is required") Map<String, Object> configuration
) {
}
