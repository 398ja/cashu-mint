package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintAlertRecord(String id,
                              String severity,
                              String message,
                              String createdAt) {

    public MintAlertRecord {
        id = ModelValidations.requireText(id, "id");
        severity = ModelValidations.requireText(severity, "severity").toUpperCase();
        message = ModelValidations.requireText(message, "message");
        createdAt = ModelValidations.requireText(createdAt, "createdAt");
    }
}
