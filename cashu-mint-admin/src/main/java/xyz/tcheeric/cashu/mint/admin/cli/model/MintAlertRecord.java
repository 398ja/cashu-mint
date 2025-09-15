package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintAlertRecord(String id,
                              String severity,
                              String message,
                              String createdAt) {

    public MintAlertRecord {
        this.id = ModelValidations.requireText(id, "id");
        this.severity = ModelValidations.requireText(severity, "severity").toUpperCase();
        this.message = ModelValidations.requireText(message, "message");
        this.createdAt = ModelValidations.requireText(createdAt, "createdAt");
    }
}
