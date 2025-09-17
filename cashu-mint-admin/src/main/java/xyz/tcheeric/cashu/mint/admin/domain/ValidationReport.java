package xyz.tcheeric.cashu.mint.admin.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Captures validation artefacts for a configuration revision.
 */
@Value
@Accessors(fluent = true)
public class ValidationReport {

    ConfigurationRevisionId revisionId;
    boolean valid;
    List<String> issues;
    Map<String, String> artefacts;
    AuditMetadata validator;
    Instant validatedAt;

    public ValidationReport(final ConfigurationRevisionId revisionId,
                            final boolean valid,
                            final List<String> issues,
                            final Map<String, String> artefacts,
                            final AuditMetadata validator,
                            final Instant validatedAt) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.valid = valid;
        this.issues = sanitizeList(issues);
        this.artefacts = Map.copyOf(artefacts == null ? Map.of() : artefacts);
        this.validator = Objects.requireNonNull(validator, "validator metadata must not be null");
        this.validatedAt = Objects.requireNonNull(validatedAt, "validatedAt must not be null");
    }

    public static ValidationReport success(final ConfigurationRevisionId revisionId,
                                           final AuditMetadata validator,
                                           final Instant validatedAt,
                                           final Map<String, String> artefacts) {
        return new ValidationReport(revisionId, true, List.of(), artefacts, validator, validatedAt);
    }

    public static ValidationReport failure(final ConfigurationRevisionId revisionId,
                                           final List<String> issues,
                                           final AuditMetadata validator,
                                           final Instant validatedAt,
                                           final Map<String, String> artefacts) {
        if (issues == null || issues.isEmpty()) {
            throw new IllegalArgumentException("failure report requires at least one issue");
        }
        return new ValidationReport(revisionId, false, issues, artefacts, validator, validatedAt);
    }

    private static List<String> sanitizeList(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(ValidationReport::sanitize)
            .toList();
    }

    private static String sanitize(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("issue must not be null");
        }
        final String sanitized = value.strip();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("issue must not be blank");
        }
        return sanitized;
    }
}
