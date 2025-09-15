package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintUserRecord(String id,
                             String displayName,
                             String role,
                             boolean active) {

    public MintUserRecord {
        id = ModelValidations.requireText(id, "id");
        displayName = ModelValidations.requireText(displayName, "displayName");
        role = ModelValidations.requireText(role, "role");
    }
}
