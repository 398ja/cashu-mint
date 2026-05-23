package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;

import java.time.Instant;

/**
 * Append-only entry on a saga's state-transition timeline. Spec 002
 * data-model § MeltSagaTransition.
 */
public interface MeltSagaTransition {

    String meltSagaId();

    int seq();

    /** {@code null} for the initial transition. */
    MeltSagaState fromState();

    MeltSagaState toState();

    String reason();

    /** {@code system}, {@code operator:<id>}, or {@code poll:<n>}. */
    String actor();

    Instant at();
}
