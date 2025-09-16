package xyz.tcheeric.cashu.mint.proto.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an auditable lifecycle transition.
 */
public record LifecycleEvent(UUID id,
                             LifecycleEventType type,
                             Instant occurredAt,
                             Map<String, String> details) {

    public LifecycleEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Map<String, String> safeDetails = details == null || details.isEmpty()
                ? Map.of()
                : Map.copyOf(details);
        details = Collections.unmodifiableMap(new LinkedHashMap<>(safeDetails));
    }

    /**
     * Convenience factory that generates a random identifier for the lifecycle event.
     *
     * @param type the lifecycle stage of the event
     * @param occurredAt when the transition happened
     * @param details additional contextual attributes to capture
     * @return a new lifecycle event instance
     */
    public static LifecycleEvent of(LifecycleEventType type, Instant occurredAt, Map<String, String> details) {
        return new LifecycleEvent(UUID.randomUUID(), type, occurredAt, details);
    }

    /**
     * Convenience factory that timestamps the event at the current instant.
     *
     * @param type the lifecycle stage of the event
     * @param details additional contextual attributes to capture
     * @return a new lifecycle event instance occurring at {@link Instant#now()}
     */
    public static LifecycleEvent now(LifecycleEventType type, Map<String, String> details) {
        return of(type, Instant.now(), details);
    }
}
