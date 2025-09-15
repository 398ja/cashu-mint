package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintUserRecord(String id,
                             String displayName,
                             String role,
                             boolean active) {

    public MintUserRecord {
        this.id = ModelValidations.requireText(id, "id");
        this.displayName = ModelValidations.requireText(displayName, "displayName");
        this.role = ModelValidations.requireText(role, "role");
    }
}
