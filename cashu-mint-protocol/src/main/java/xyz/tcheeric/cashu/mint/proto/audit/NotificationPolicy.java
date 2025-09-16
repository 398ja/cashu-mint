package xyz.tcheeric.cashu.mint.proto.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Describes a notification policy that should be triggered for a lifecycle transition.
 */
public record NotificationPolicy(UUID id, String name) {

    public NotificationPolicy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Convenience factory that generates an identifier for the policy.
     *
     * @param name the human readable policy name
     * @return the policy descriptor
     */
    public static NotificationPolicy of(String name) {
        return new NotificationPolicy(UUID.randomUUID(), name);
    }
}
