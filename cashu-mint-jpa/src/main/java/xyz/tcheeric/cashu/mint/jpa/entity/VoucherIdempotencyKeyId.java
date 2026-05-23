package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Spec 003 — composite primary key for {@code voucher_idempotency_key}:
 * keys are scoped per-principal so two callers can submit the same
 * client-supplied key without colliding.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class VoucherIdempotencyKeyId implements Serializable {

    @Column(name = "idempotency_key", length = 255, nullable = false)
    private String idempotencyKey;

    @Column(name = "principal_id", length = 255, nullable = false)
    private String principalId;
}
