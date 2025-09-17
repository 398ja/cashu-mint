package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Represents the delta between two configuration revisions.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationDiff {

    Map<String, ConfigurationValue> added;
    Map<String, ParameterChange> changed;
    Map<String, ConfigurationValue> removed;

    public ConfigurationDiff(final Map<String, ConfigurationValue> added,
                             final Map<String, ParameterChange> changed,
                             final Map<String, ConfigurationValue> removed) {
        this.added = Map.copyOf(added);
        this.changed = Map.copyOf(changed);
        this.removed = Map.copyOf(removed);
    }

    public static ConfigurationDiff between(final ConfigurationSet previous, final ConfigurationSet next) {
        Objects.requireNonNull(previous, "previous configuration must not be null");
        Objects.requireNonNull(next, "next configuration must not be null");
        final Map<String, ConfigurationValue> added = new LinkedHashMap<>();
        final Map<String, ParameterChange> changed = new LinkedHashMap<>();
        final Map<String, ConfigurationValue> removed = new LinkedHashMap<>();

        previous.parameters().forEach((key, value) -> {
            if (!next.parameters().containsKey(key)) {
                removed.put(key, value);
            } else {
                final ConfigurationValue nextValue = next.parameters().get(key);
                if (!value.equals(nextValue)) {
                    changed.put(key, new ParameterChange(value, nextValue));
                }
            }
        });

        next.parameters().forEach((key, value) -> {
            if (!previous.parameters().containsKey(key)) {
                added.put(key, value);
            }
        });

        return new ConfigurationDiff(added, changed, removed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && changed.isEmpty() && removed.isEmpty();
    }

    public static ConfigurationDiff empty() {
        return new ConfigurationDiff(Map.of(), Map.of(), Map.of());
    }

    @Value
    @Accessors(fluent = true)
    public static class ParameterChange {

        ConfigurationValue previousValue;
        ConfigurationValue nextValue;

        public ParameterChange(final ConfigurationValue previousValue, final ConfigurationValue nextValue) {
            this.previousValue = Objects.requireNonNull(previousValue, "previous value must not be null");
            this.nextValue = Objects.requireNonNull(nextValue, "next value must not be null");
        }
    }
}
