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
 * <p>The actor on an audit entry is the Operator the server authenticated. No request field or
 * header offers one — an audit trail that records a claimed identity is evidence of nothing. See
 * ADR-0005.
 */
@Component
public class OperatorIdentity {

  /**
   * The id of the Operator authenticated for the request in flight.
   *
   * @return the authenticated Operator's id
   * @throws AdminServiceException when no Operator was resolved
   */
  public String currentOperatorId() {
    final var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      final HttpServletRequest request = servletAttributes.getRequest();
      final Object operator = request.getAttribute(AuthenticatedOperator.ATTRIBUTE);
      if (operator instanceof AuthenticatedOperator authenticated) {
        return authenticated.operatorId();
      }
    }
    throw new AdminServiceException(
        HttpStatus.UNAUTHORIZED, "unauthorized", "No authenticated operator on the request");
  }
}
