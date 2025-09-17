package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated validation outcome for configuration submissions.
 */
public record ValidationSummary(
        boolean valid,
        List<String> errors,
        List<String> warnings,
        String summary
) {

    public ValidationSummary {
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(warnings, "warnings");
        Objects.requireNonNull(summary, "summary");
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
