package xyz.tcheeric.cashu.mint.rest.admin.dto.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;

/**
 * Request payload for previewing configuration changes prior to applying them.
 */
public record PreviewConfigurationRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotNull(message = "proposed configuration is required") Map<String, Object> proposedConfiguration,
        @NotBlank(message = "base revision id is required") String baseRevisionId
) {
}
