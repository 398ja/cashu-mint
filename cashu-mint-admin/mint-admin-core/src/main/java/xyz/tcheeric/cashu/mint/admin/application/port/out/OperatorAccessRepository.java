package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence port for administrator and operator access records.
 */
public interface OperatorAccessRepository {

    /**
     * Retrieve all operator accounts.
     *
     * @return list of all operator accounts
     */
    List<OperatorAccessAccount> findAll();

    /**
     * Persist a new operator record.
     *
     * @param account account state to persist
     * @return true when the account is created; false when it already exists
     */
    boolean create(OperatorAccessAccount account);

    /**
     * Retrieve an existing operator account by identifier.
     *
     * @param accountId account identifier
     * @return account details when present
     */
    Optional<OperatorAccessAccount> findById(String accountId);

    /**
     * Persist updated operator account state.
     *
     * @param account account state to persist
     */
    void update(OperatorAccessAccount account);

    /**
     * Immutable representation of a persisted operator account.
     */
    record OperatorAccessAccount(String accountId,
                                 String displayName,
                                 String email,
                                 Set<String> roles,
                                 boolean active,
                                 int credentialResetCount,
                                 String lastResetToken,
                                 Instant lastResetAt) {

        public OperatorAccessAccount {
            Objects.requireNonNull(accountId, "account id must not be null");
            Objects.requireNonNull(roles, "roles must not be null");
            roles = Set.copyOf(roles);
        }
    }
}
