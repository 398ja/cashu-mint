package xyz.tcheeric.cashu.mint.admin.domain;

/**
 * What an Operator may do, named once so the REST module can hand the same set
 * to an authentication protocol without the domain knowing that protocol exists.
 *
 * <p>Keys are the wire form. They are lower-case and colon-separated because
 * that is what NAP's permission registry publishes to clients, and a permission
 * renamed on one side of that wire is a permission silently never granted.
 */
public enum AdminPermission {

    MINT_LIFECYCLE("mint:lifecycle", "Advance a mint through its lifecycle"),
    AUDIT_READ("audit:read", "Read the audit trail"),
    USERS_MANAGE("users:manage", "Create, update and deactivate operator accounts"),
    OPERATIONS_EXECUTE("operations:execute", "Run maintenance and force-close controls"),
    OPERATORS_MANAGE("operators:manage", "Grant and revoke operator roles");

    private final String key;
    private final String description;

    AdminPermission(final String key, final String description) {
        this.key = key;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String description() {
        return description;
    }
}
