package xyz.tcheeric.cashu.mint.admin.cli.model;

import java.util.UUID;

/**
 * Payload describing a lifecycle command to be executed against a mint.
 */
public record MintLifecycleRequest(String mintId,
                                   String operatorId,
                                   String versionTag) {

    public MintLifecycleRequest {
        mintId = ModelValidations.requireMintId(mintId);
        operatorId = ModelValidations.requireText(operatorId, "operatorId");
        versionTag = ModelValidations.requireText(versionTag, "versionTag");
        validateOperator(operatorId);
    }

    private static void validateOperator(final String operatorId) {
        try {
            UUID.fromString(operatorId);
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("operatorId must be a valid UUID", ex);
        }
    }
}
