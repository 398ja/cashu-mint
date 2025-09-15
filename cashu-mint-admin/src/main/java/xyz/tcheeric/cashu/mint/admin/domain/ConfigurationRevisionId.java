package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Revision identifier for a configuration snapshot.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationRevisionId implements Comparable<ConfigurationRevisionId> {

    long value;

    private ConfigurationRevisionId(final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("configuration revision id must be positive");
        }
        this.value = value;
    }

    public static ConfigurationRevisionId of(final long value) {
        return new ConfigurationRevisionId(value);
    }

    public ConfigurationRevisionId next() {
        return new ConfigurationRevisionId(value + 1);
    }

    public boolean isAfter(final ConfigurationRevisionId other) {
        Objects.requireNonNull(other, "other revision id must not be null");
        return this.value > other.value;
    }

    @Override
    public int compareTo(final ConfigurationRevisionId other) {
        Objects.requireNonNull(other, "other revision id must not be null");
        return Long.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
