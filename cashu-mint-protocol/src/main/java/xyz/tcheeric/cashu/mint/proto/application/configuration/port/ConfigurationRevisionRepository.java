package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.ConfigurationRevision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApprovalStateSnapshot;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationRevisionDraft;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.DiffArtifact;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationSummary;

/**
 * Repository abstraction for interacting with configuration revisions.
 */
public interface ConfigurationRevisionRepository {

    ConfigurationRevision saveDraft(ConfigurationRevisionDraft draft,
                                    ValidationSummary validationSummary,
                                    DiffArtifact diffArtifact);

    Optional<ConfigurationRevision> findById(UUID revisionId);

    Optional<ConfigurationRevision> findLatestApplied(String scope);

    ConfigurationRevision save(ConfigurationRevision revision);

    ConfigurationRevision updateApprovalState(UUID revisionId, ApprovalStateSnapshot approvalState);

    ConfigurationRevision markApplied(UUID revisionId, Instant appliedAt);

    ConfigurationRevision markRolledBack(UUID appliedRevisionId,
                                         UUID rollbackTargetRevisionId,
                                         Instant rolledBackAt);
}
