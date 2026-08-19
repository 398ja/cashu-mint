package xyz.tcheeric.cashu.mint.admin.rest.config;

import java.util.Objects;
import java.util.Set;

/**
 * The operator the authentication filter resolved from the presented credential.
 *
 * <p>Placed on the request by {@link AdminAuthenticationFilter} and read by
 * {@link AdminRbacFilter}. Roles are a property of the operator, never of the
 * request — see ADR-0005.
 *
 * @param operatorId identifier of the authenticated operator
 * @param displayName human-readable name, for the audit trail
 * @param roles roles the operator holds
 */
public record AuthenticatedOperator(String operatorId, String displayName, Set<String> roles) {

    /** Request attribute under which the resolved operator is published. */
    public static final String ATTRIBUTE = "cashu.admin.authenticatedOperator";

    public AuthenticatedOperator {
        Objects.requireNonNull(operatorId, "operator id must not be null");
        Objects.requireNonNull(displayName, "display name must not be null");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }
}
