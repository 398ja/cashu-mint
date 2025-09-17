package xyz.tcheeric.cashu.mint.proto.application.configuration;

import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApproverDecisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApproverDecisionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApplyRevisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApplyRevisionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationSubmissionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationSubmissionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.RollbackRevisionRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.RollbackRevisionResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.StagedApprovalRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.StagedApprovalResponse;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationPreviewRequest;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationPreviewResponse;

/**
 * Application interactor exposing configuration management workflows to adapters.
 */
public interface ManageConfigurationInteractor {

    ConfigurationSubmissionResponse submitRevision(ConfigurationSubmissionRequest request);

    ValidationPreviewResponse previewValidation(ValidationPreviewRequest request);

    StagedApprovalResponse requestStagedApproval(StagedApprovalRequest request);

    ApproverDecisionResponse recordApproverDecision(ApproverDecisionRequest request);

    ApplyRevisionResponse applyApprovedRevision(ApplyRevisionRequest request);

    RollbackRevisionResponse rollbackRevision(RollbackRevisionRequest request);
}
