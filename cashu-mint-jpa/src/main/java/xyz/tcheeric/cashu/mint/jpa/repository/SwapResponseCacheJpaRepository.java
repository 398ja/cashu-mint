package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.SwapResponseCacheEntity;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data backing for the {@code swap_response_cache} table (issue #482).
 *
 * <p>Operator query, what a wallet replaying a swap right now would get back:
 *
 * <pre>{@code
 * SELECT request_fingerprint, created_at, expires_at
 *   FROM swap_response_cache
 *  WHERE expires_at > now()
 *  ORDER BY created_at DESC;
 * }</pre>
 */
public interface SwapResponseCacheJpaRepository extends JpaRepository<SwapResponseCacheEntity, String> {

    /**
     * The response stored for a fingerprint, provided it has not expired.
     *
     * <p>Expiry is checked here rather than left to the purger, so an entry is never replayed
     * past the ttl the mint advertised, however far behind the purge is.
     */
    @Query("""
            SELECT c FROM SwapResponseCacheEntity c
             WHERE c.requestFingerprint = :fingerprint
               AND c.expiresAt > :now
            """)
    Optional<SwapResponseCacheEntity> findUnexpired(@Param("fingerprint") String fingerprint,
                                                    @Param("now") Instant now);

    /**
     * Deletes every entry that expired before {@code cutoff}.
     *
     * @return how many entries were removed
     */
    @Modifying
    @Transactional(transactionManager = "mintTransactionManager")
    @Query("DELETE FROM SwapResponseCacheEntity c WHERE c.expiresAt <= :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
