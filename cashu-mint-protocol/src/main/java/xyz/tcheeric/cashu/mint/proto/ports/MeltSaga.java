package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;

import java.time.Instant;

/**
 * Domain-level view of a NUT-05 melt saga. Lives in the protocol module so
 * {@code MeltTask} and the reconciler can reason about saga state without
 * depending on Hibernate. The {@code cashu-mint-jpa} adapter implements this
 * as a JPA entity.
 *
 * <p>Spec 002 data-model § MeltSaga.
 */
public interface MeltSaga {

    String meltSagaId();

    String quoteId();

    long invoiceAmount();

    long exactFeeReserve();

    Long assertedFeeReserve();

    long inputAmount();

    int proofCount();

    MeltSagaState currentState();

    String paymentHash();

    String provider();

    String providerEventId();

    String paymentOutcomeReason();

    String meltResponseCache();

    String changeOutputsHash();

    String changeSignaturesJson();

    Instant createdAt();

    Instant updatedAt();
}
