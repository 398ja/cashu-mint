package xyz.tcheeric.cashu.mint.admin.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Represents the value of a configuration parameter.
 *
 * <p>Values are either plain-text strings or a {@link ConfigurationSecret} reference that can be
 * resolved by an external vault. Persisted revisions only store the reference in order to avoid
 * leaking sensitive material.</p>
 */
@Value
@Accessors(fluent = true)
public class ConfigurationValue {

    String value;
    ConfigurationSecret secret;

    @JsonCreator
    public ConfigurationValue(@JsonProperty("value") final String value,
                              @JsonProperty("secret") final ConfigurationSecret secret) {
        if (value != null && secret != null) {
            throw new IllegalArgumentException("value cannot contain both clear text and secret reference");
        }
        if (value == null && secret == null) {
            throw new IllegalArgumentException("value must contain either clear text or secret reference");
        }
        this.value = value;
        this.secret = secret;
    }

    public static ConfigurationValue ofPlainText(final String value) {
        final String sanitized = sanitize(value, "plain-text value");
        return new ConfigurationValue(sanitized, null);
    }

    public static ConfigurationValue ofSecret(final ConfigurationSecret secret) {
        return new ConfigurationValue(null, Objects.requireNonNull(secret, "secret must not be null"));
    }

    public boolean isSecret() {
        return secret != null;
    }

    public String resolvedValue() {
        if (isSecret()) {
            throw new IllegalStateException("secret values cannot be resolved directly");
        }
        return value;
    }

    private static String sanitize(final String value, final String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        final String sanitized = value.strip();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return sanitized;
    }
}
