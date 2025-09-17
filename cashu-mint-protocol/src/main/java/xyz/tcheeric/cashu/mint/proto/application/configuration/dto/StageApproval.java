package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of approval status for a single stage.
 */
public record StageApproval(
        String stage,
        Decision decision,
        List<String> pendingApprovers,
        Instant decidedAt,
        String decidedBy,
        String comment
) {

    public StageApproval {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(pendingApprovers, "pendingApprovers");
        pendingApprovers = List.copyOf(pendingApprovers);
    }

    public boolean isPending() {
        return decision == Decision.PENDING;
    }
}
