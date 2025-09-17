package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Result of applying a lifecycle transition to a configuration revision.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationSetTransition {

    ConfigurationSet configurationSet;
    ConfigurationLifecycleEvent lifecycleEvent;

    public ConfigurationSetTransition(final ConfigurationSet configurationSet,
                                      final ConfigurationLifecycleEvent lifecycleEvent) {
        this.configurationSet = Objects.requireNonNull(configurationSet, "configuration set must not be null");
        this.lifecycleEvent = Objects.requireNonNull(lifecycleEvent, "lifecycle event must not be null");
    }
}
