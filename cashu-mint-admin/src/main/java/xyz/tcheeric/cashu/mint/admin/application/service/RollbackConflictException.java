package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Exception raised when a rollback operation conflicts with the current configuration state.
 */
public class RollbackConflictException extends RuntimeException {

    private final String mintId;
    private final long revisionId;

    public RollbackConflictException(final MintId mintId,
                                     final ConfigurationRevisionId revisionId,
                                     final String message,
                                     final Throwable cause) {
        super(message, cause);
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
