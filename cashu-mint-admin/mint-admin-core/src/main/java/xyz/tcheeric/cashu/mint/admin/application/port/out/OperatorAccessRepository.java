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
     * Look up an operator by the hash of their credential.
     *
     * @param credentialHash SHA-256 hash of the presented credential
     * @return the matching account when one exists
     */
    Optional<OperatorAccessAccount> findByCredentialHash(String credentialHash);

    /**
     * Look up an operator by their Nostr public key.
     *
     * @param pubkey lower-case hex public key, as NAP reports it
     * @return the matching account when one exists
     */
    Optional<OperatorAccessAccount> findByPubkey(String pubkey);

    /**
     * Immutable representation of a persisted operator account.
     */
    record OperatorAccessAccount(String accountId,
                                 String displayName,
                                 String email,
                                 Set<String> roles,
                                 boolean active,
                                 int credentialResetCount,
                                 String credentialHash,
                                 Instant lastResetAt,
                                 String pubkey) {

        public OperatorAccessAccount {
            Objects.requireNonNull(accountId, "account id must not be null");
            Objects.requireNonNull(roles, "roles must not be null");
            roles = Set.copyOf(roles);
            // NAP reports the pubkey as lower-case hex, and lookups compare on it,
            // so an upper-case key stored here would be one nobody could sign in with.
            pubkey = pubkey == null ? null : pubkey.toLowerCase();
        }
    }
}
