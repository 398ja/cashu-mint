package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable representation of configuration parameters for a mint.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationSet {

    ConfigurationRevisionId revisionId;
    Map<String, String> parameters;
    AuditMetadata auditMetadata;

    public ConfigurationSet(final ConfigurationRevisionId revisionId,
                            final Map<String, String> parameters,
                            final AuditMetadata auditMetadata) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.parameters = Map.copyOf(validateParameters(parameters));
        this.auditMetadata = Objects.requireNonNull(auditMetadata, "audit metadata must not be null");
    }

    private Map<String, String> validateParameters(final Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        final Map<String, String> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : parameters.entrySet()) {
            final String key = requireNonBlank(entry.getKey(), "parameter key");
            final String value = requireNonBlank(entry.getValue(), "parameter value");
            copy.put(key, value);
        }
        return copy;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public ConfigurationSet updateParameter(final String key,
                                            final String value,
                                            final ConfigurationRevisionId nextRevision,
                                            final AuditMetadata metadata) {
        final ConfigurationRevisionId revision = Objects.requireNonNull(nextRevision, "next revision must not be null");
        if (!revision.isAfter(this.revisionId)) {
            throw new IllegalArgumentException("next revision must be greater than current revision");
        }
        final Map<String, String> updated = new LinkedHashMap<>(parameters);
        updated.put(requireNonBlank(key, "parameter key"), requireNonBlank(value, "parameter value"));
        return new ConfigurationSet(revision, updated, Objects.requireNonNull(metadata, "audit metadata must not be null"));
    }
}
