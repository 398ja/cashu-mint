package xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.ActorDto;

/**
 * Request payload for updating lifecycle metadata or configuration bindings for an existing mint.
 */
public record UpdateMintRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotNull(message = "metadata is required") @Valid MintMetadataDto metadata,
        @NotNull(message = "configuration is required") Map<String, Object> configuration,
        @NotBlank(message = "revision id is required") String revisionId
) {
}
