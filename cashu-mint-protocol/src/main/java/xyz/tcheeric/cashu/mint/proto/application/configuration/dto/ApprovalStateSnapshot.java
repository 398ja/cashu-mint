package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregated approval state across all stages.
 */
public record ApprovalStateSnapshot(
        Map<String, StageApproval> stages,
        boolean fullyApproved,
        Optional<String> nextPendingStage
) {

    public ApprovalStateSnapshot {
        Objects.requireNonNull(stages, "stages");
        Objects.requireNonNull(nextPendingStage, "nextPendingStage");
        stages = Map.copyOf(stages);
    }

    public StageApproval stage(String stageName) {
        return stages.get(stageName);
    }
}
