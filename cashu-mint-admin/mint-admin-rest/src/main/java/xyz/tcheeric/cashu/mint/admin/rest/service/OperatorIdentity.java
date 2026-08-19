package xyz.tcheeric.cashu.mint.admin.rest.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import xyz.tcheeric.cashu.mint.admin.rest.config.AuthenticatedOperator;

/**
 * Resolves the Operator the authentication filter identified, for the Audit Trail.
 *
 * <p>The actor on an audit entry must be the Operator the server authenticated,
 * not one a request body claims to be. A request that names someone else is
 * refused rather than silently preferring one or the other — an audit trail that
 * records a claimed identity is evidence of nothing. See ADR-0005.
 */
@Component
public class OperatorIdentity {

    /**
     * The Operator authenticated for the request in flight.
     *
     * @return the authenticated Operator
     * @throws AdminServiceException when no Operator was resolved
     */
    public AuthenticatedOperator current() {
        final var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            final HttpServletRequest request = servletAttributes.getRequest();
            final Object operator = request.getAttribute(AuthenticatedOperator.ATTRIBUTE);
            if (operator instanceof AuthenticatedOperator authenticated) {
                return authenticated;
            }
        }
        throw new AdminServiceException(HttpStatus.UNAUTHORIZED, "unauthorized",
            "No authenticated operator on the request");
    }

    /**
     * The actor to record, having checked the request does not claim to be someone else.
     *
     * @param claimedOperatorId operator id the request body carries, may be null
     * @return the authenticated Operator's id
     * @throws AdminServiceException when the body names a different Operator
     */
    public String requireActor(final String claimedOperatorId) {
        final AuthenticatedOperator operator = current();
        if (claimedOperatorId != null
            && !claimedOperatorId.isBlank()
            && !claimedOperatorId.equals(operator.operatorId())) {
            throw new AdminServiceException(HttpStatus.FORBIDDEN, "operator_mismatch",
                "Request names operator " + claimedOperatorId
                    + " but was authenticated as " + operator.operatorId());
        }
        return operator.operatorId();
    }
}
