package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves the Operator the NAP session identified, for the Audit Trail.
 *
 * <p>The actor on an audit entry is the Operator the server authenticated. No request field or
 * header offers one — an audit trail that records a claimed identity is evidence of nothing. See
 * ADR-0005.
 */
@Component
public class OperatorIdentity {

  private final OperatorAccessRepository operatorAccessRepository;

  public OperatorIdentity(final OperatorAccessRepository operatorAccessRepository) {
    this.operatorAccessRepository =
        Objects.requireNonNull(operatorAccessRepository, "operator access repository");
  }

  /**
   * The id of the Operator authenticated for the request in flight.
   *
   * @return the authenticated Operator's id
   * @throws AdminServiceException when no session authenticated the request
   */
  public String currentOperatorId() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new AdminServiceException(
          HttpStatus.UNAUTHORIZED, "unauthorized", "No authenticated operator on the request");
    }
    // NAP names the principal by public key; the audit trail names it by account id.
    final String pubkey = authentication.getName();
    return operatorAccessRepository
        .findByPubkey(pubkey)
        .map(OperatorAccessRepository.OperatorAccessAccount::accountId)
        // The Super Administrator is configuration, not a row, so there is no account id to
        // read. Deriving one from the key keeps their entries attributable and distinct,
        // where a shared sentinel would merge every Super Administrator into one actor.
        .orElseGet(() -> derivedAccountId(pubkey));
  }

  /**
   * The account id an npub with no stored profile is known by.
   *
   * @param pubkey lower-case hex public key, as NAP reports it
   * @return a stable id derived from the key
   */
  public static String derivedAccountId(final String pubkey) {
    // Lower-cased here rather than at the call sites: the hash is byte-exact, so the same
        // key in a different case would otherwise derive a different operator.
        return UUID.nameUUIDFromBytes(pubkey.toLowerCase().getBytes(StandardCharsets.UTF_8)).toString();
  }
}
