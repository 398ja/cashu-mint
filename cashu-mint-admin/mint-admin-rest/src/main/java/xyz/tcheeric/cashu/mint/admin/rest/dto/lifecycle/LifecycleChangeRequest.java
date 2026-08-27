package xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload describing a lifecycle state transition such as pause, resume, or retire.
 */
public record LifecycleChangeRequest(
        @NotBlank(message = "reason is required") String reason,
        String correlationId
) {
}
