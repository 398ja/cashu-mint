package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.List;
import java.util.Objects;

/**
 * Advice for adapters to communicate to callers about the next workflow steps.
 */
public record NextStepGuidance(
        String message,
        List<String> recommendedActions
) {

    public NextStepGuidance {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(recommendedActions, "recommendedActions");
        recommendedActions = List.copyOf(recommendedActions);
    }

    public static NextStepGuidance empty() {
        return new NextStepGuidance("", List.of());
    }
}
