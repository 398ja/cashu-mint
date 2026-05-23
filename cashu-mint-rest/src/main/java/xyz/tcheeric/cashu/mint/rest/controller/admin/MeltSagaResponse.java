package xyz.tcheeric.cashu.mint.rest.controller.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaTransition;

import java.time.Instant;
import java.util.List;

/**
 * Spec 002 T312 — DTO for the admin saga query endpoint. JSON shape is
 * pinned by {@code MeltSagaQueryIT} once that IT lands.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeltSagaResponse(
        String meltSagaId,
        String quoteId,
        MeltSagaState currentState,
        long invoiceAmount,
        long exactFeeReserve,
        Long assertedFeeReserve,
        long inputAmount,
        int proofCount,
        String paymentHash,
        String provider,
        String providerEventId,
        String paymentOutcomeReason,
        Instant createdAt,
        Instant updatedAt,
        List<TransitionEntry> transitions
) {

    public record TransitionEntry(
            int seq,
            MeltSagaState fromState,
            MeltSagaState toState,
            String reason,
            String actor,
            Instant at
    ) {
        public static TransitionEntry from(MeltSagaTransition t) {
            return new TransitionEntry(t.seq(), t.fromState(), t.toState(),
                    t.reason(), t.actor(), t.at());
        }
    }

    public static MeltSagaResponse from(MeltSaga s, List<MeltSagaTransition> transitions) {
        return new MeltSagaResponse(
                s.meltSagaId(),
                s.quoteId(),
                s.currentState(),
                s.invoiceAmount(),
                s.exactFeeReserve(),
                s.assertedFeeReserve(),
                s.inputAmount(),
                s.proofCount(),
                s.paymentHash(),
                s.provider(),
                s.providerEventId(),
                s.paymentOutcomeReason(),
                s.createdAt(),
                s.updatedAt(),
                transitions.stream().map(TransitionEntry::from).toList()
        );
    }
}
