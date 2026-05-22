package xyz.tcheeric.cashu.mint.jpa.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Composite primary key for {@link WebhookEventEntity}. JPA requires this to be
 * a {@link Serializable} POJO with equals/hashCode over the id fields.
 *
 * <p>Spec 001: FR-006 keys webhook idempotency by {@code (provider, provider_event_id)}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class WebhookEventId implements Serializable {

    private String provider;
    private String providerEventId;
}
