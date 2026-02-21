package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintStatusRequest(String mintId) {

    public static final String DEFAULT_MINT_ID = "default-mint";

    public MintStatusRequest {
        mintId = ModelValidations.requireMintId(mintId);
    }

    public static MintStatusRequest defaultRequest() {
        return new MintStatusRequest(DEFAULT_MINT_ID);
    }
}
