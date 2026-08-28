package xyz.tcheeric.cashu.mint.admin.domain;

/**
 * What an Operator may do, named once so the REST module can hand the same set to the
 * authentication protocol without the domain knowing the protocol exists.
 *
 * <p>Keys are the wire form. They are lower-case and colon-separated because NAP's
 * permission registry publishes them to clients, and a permission renamed on one side of
 * the wire is a permission silently never granted.
 */
public enum AdminPermission {

    MINT_LIFECYCLE(Keys.MINT_LIFECYCLE, "Advance a mint through its lifecycle"),
    AUDIT_READ(Keys.AUDIT_READ, "Read the audit trail"),
    OPERATIONS_EXECUTE(Keys.OPERATIONS_EXECUTE, "Run maintenance and force-close controls"),
    USERS_MANAGE(Keys.USERS_MANAGE, "Create, update and deactivate operator accounts"),
    OPERATORS_MANAGE(Keys.OPERATORS_MANAGE, "Grant and revoke operator roles"),
    DASHBOARD_READ(Keys.DASHBOARD_READ, "Read the operator dashboard");

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

    /**
     * The same keys as compile-time constants.
     *
     * <p>Annotation values must be constants, so {@code @RequiresPermission} cannot call
     * {@link #key()}. The enum reads its key from here rather than the reverse, so the two
     * cannot drift.
     */
    public static final class Keys {

        public static final String MINT_LIFECYCLE = "mint:lifecycle";
        public static final String AUDIT_READ = "audit:read";
        public static final String OPERATIONS_EXECUTE = "operations:execute";
        public static final String USERS_MANAGE = "users:manage";
        public static final String OPERATORS_MANAGE = "operators:manage";
        public static final String DASHBOARD_READ = "dashboard:read";

        private Keys() {
        }
    }
}
