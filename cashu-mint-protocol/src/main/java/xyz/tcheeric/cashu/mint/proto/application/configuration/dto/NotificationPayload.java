package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload sent to notification gateways.
 */
public record NotificationPayload(
        UUID revisionId,
        String template,
        Map<String, Object> attributes
) {

    public NotificationPayload {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(attributes, "attributes");
        attributes = Map.copyOf(attributes);
    }
}
