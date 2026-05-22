package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;

/**
 * Spring Data JPA backing for {@code mint_quote}. The single non-derived query
 * is {@link #casLifecycle}, which compiles to a conditional UPDATE so that
 * concurrent writers cannot both succeed in advancing the lifecycle state.
 *
 * <p>Spec 001: research §R3 (compare-and-set).
 */
@Repository
public interface MintQuoteJpaRepository extends JpaRepository<MintQuoteEntity, String> {

    /**
     * Conditional UPDATE on {@code lifecycle_state}: only sets {@code to} when
     * the current value is {@code from}. Returns {@code 1} on success, {@code 0}
     * if a concurrent writer already advanced the state. Callers MUST re-read
     * on a return of {@code 0} and decide whether to replay or reject.
     */
    @Modifying
    @Query("UPDATE MintQuoteEntity q "
            + "SET q.lifecycleState = :to, q.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE q.quoteId = :id AND q.lifecycleState = :from")
    int casLifecycle(@Param("id") String id,
                     @Param("from") LifecycleState from,
                     @Param("to") LifecycleState to);
}
