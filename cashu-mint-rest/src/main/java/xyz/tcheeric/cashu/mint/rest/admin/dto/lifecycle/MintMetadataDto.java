package xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Describes operator-supplied metadata for a mint lifecycle request.
 */
public record MintMetadataDto(
        @NotBlank(message = "mint display name is required") String displayName,
        @Size(max = 512, message = "description must be at most 512 characters") String description,
        List<@NotBlank(message = "tags must not be blank") String> tags
) {
}
