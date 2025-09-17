package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.ConfigurationRevision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApprovalStateSnapshot;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.Decision;

/**
 * Encapsulates approval workflow policies.
 */
public interface ApprovalPolicyEngine {

    ApprovalStateSnapshot initializeApprovals(ConfigurationRevision revision);

    ApprovalStateSnapshot requestStage(ConfigurationRevision revision, String stage, String requestedBy);

    ApprovalStateSnapshot recordDecision(ConfigurationRevision revision,
                                         String stage,
                                         String approverId,
                                         Decision decision,
                                         String comment);
}
