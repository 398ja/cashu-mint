package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence port for administrator operator access records.
 */
public interface OperatorAccessRepository {

    /**
     * Retrieve all operator accounts.
     *
     * @return a list of all operator accounts
     */
    List<OperatorAccessAccount> findAll();

    /**
     * Persist a new operator record.
     *
     * @param account the account state to persist
     * @return true when the account was created; false when it already exists
     */
    boolean create(OperatorAccessAccount account);

    /**
     * Retrieve an existing operator account by identifier.
     *
     * @param accountId the account identifier
     * @return the account details when present
     */
    Optional<OperatorAccessAccount> findById(String accountId);

    /**
     * Persist updated operator account state.
     *
     * @param account the account state to persist
     */
    void update(OperatorAccessAccount account);

    /**
     * Look up an operator by Nostr public key.
     *
     * @param pubkey the lower-case hex public key, as NAP reports it
     * @return the matching account when one exists
     */
    Optional<OperatorAccessAccount> findByPubkey(String pubkey);

    /**
     * Immutable representation of a persisted operator account.
     */
    record OperatorAccessAccount(String accountId, String displayName, String email,
                                 Set<String> roles, boolean active, String pubkey) {

        public OperatorAccessAccount {
            Objects.requireNonNull(accountId, "account id must not be null");
            Objects.requireNonNull(roles, "roles must not be null");
            roles = Set.copyOf(roles);
            if (pubkey == null || pubkey.isBlank()) {
                // The public key is the only way in, so an operator without one is a row
                // nobody can sign in as — a silent lockout rather than a rejected write.
                throw new IllegalArgumentException("pubkey must not be blank");
            }
            // NAP reports the pubkey as lower-case hex, and lookups compare on it,
            // so an upper-case key stored here would be one nobody could sign in with.
            pubkey = pubkey.toLowerCase();
        }
    }
}
