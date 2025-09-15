package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintUsersRequest(String mintId, boolean includeInactive) {

    public static final String DEFAULT_MINT_ID = "default-mint";

    public MintUsersRequest {
        this.mintId = ModelValidations.requireMintId(mintId);
    }
}
