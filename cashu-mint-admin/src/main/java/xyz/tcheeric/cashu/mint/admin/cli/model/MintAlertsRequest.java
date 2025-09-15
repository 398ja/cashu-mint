package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintAlertsRequest(String mintId, String severity) {

    public static final String DEFAULT_MINT_ID = "default-mint";
    public static final String DEFAULT_SEVERITY = "INFO";

    public MintAlertsRequest {
        this.mintId = ModelValidations.requireMintId(mintId);
        this.severity = ModelValidations.requireText(severity, "severity").toUpperCase();
    }
}
