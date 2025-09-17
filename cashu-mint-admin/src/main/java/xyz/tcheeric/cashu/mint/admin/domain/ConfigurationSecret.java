package xyz.tcheeric.cashu.mint.admin.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Reference to a secret stored outside of the configuration history.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationSecret {

    String reference;
    SecretMaterialization materialization;

    @JsonCreator
    public ConfigurationSecret(@JsonProperty("reference") final String reference,
                               @JsonProperty("materialization") final SecretMaterialization materialization) {
        this.reference = sanitize(reference);
        this.materialization = Objects.requireNonNull(materialization, "materialization must not be null");
    }

    public static ConfigurationSecret namedReference(final String reference) {
        return new ConfigurationSecret(reference, SecretMaterialization.VAULT_REFERENCE);
    }

    public static ConfigurationSecret ephemeralToken(final String reference) {
        return new ConfigurationSecret(reference, SecretMaterialization.EPHEMERAL_TOKEN);
    }

    public enum SecretMaterialization {
        VAULT_REFERENCE,
        EPHEMERAL_TOKEN
    }

    private static String sanitize(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("secret reference must not be null");
        }
        final String sanitized = value.strip();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("secret reference must not be blank");
        }
        return sanitized;
    }
}
