package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.List;

import xyz.tcheeric.cashu.mint.admin.domain.ApprovalRecord;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence port for recording and retrieving configuration approval history.
 */
public interface ConfigurationApprovalRepository {

    /**
     * Record a newly granted approval for the supplied revision.
     *
     * @param mintId the mint identifier
     * @param approvalRecord the approval metadata
     */
    void recordApproval(MintId mintId, ApprovalRecord approvalRecord);

    /**
     * Retrieve previously recorded approvals for the supplied revision.
     *
     * @param mintId the mint identifier
     * @param revisionId the configuration revision identifier
     * @return immutable list of approval records
     */
    List<ApprovalRecord> findApprovals(MintId mintId, ConfigurationRevisionId revisionId);
}
