package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Exception raised when an operation requires an approval that has not been recorded.
 */
public class MissingApprovalException extends RuntimeException {

    private final String mintId;
    private final long revisionId;

    public MissingApprovalException(final MintId mintId, final ConfigurationRevisionId revisionId) {
        super("Missing approval for configuration revision %s of mint %s".formatted(revisionId.value(), mintId.asString()));
        this.mintId = mintId.asString();
        this.revisionId = revisionId.value();
    }

    public String mintId() {
        return mintId;
    }

    public long revisionId() {
        return revisionId;
    }
}
