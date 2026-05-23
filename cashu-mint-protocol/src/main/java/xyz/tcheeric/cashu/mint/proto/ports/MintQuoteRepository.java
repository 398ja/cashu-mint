package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;

import java.util.Optional;

/**
 * Port used by protocol tasks to read and transition durable mint quote state.
 * The adapter (cashu-mint-jpa) backs this with PostgreSQL + Hibernate Envers
 * audit. Lifecycle transitions go through {@link #casLifecycle}, which compiles
 * down to a conditional UPDATE on {@code mint_quote.lifecycle_state}; callers
 * MUST treat a return of {@code 0} as "concurrent writer advanced state" and
 * re-read.
 *
 * <p>Spec 001: research §R3 (compare-and-set semantics) and data-model §
 * MintQuote.
 */
public interface MintQuoteRepository {

    /**
     * Loads a mint quote by primary key.
     *
     * @param quoteId the provider- or mint-issued quote id
     * @return the quote if persisted, otherwise empty
     */
    Optional<MintQuote> findById(String quoteId);

    /**
     * Inserts or updates a quote in {@code mint_quote}. Insert is the normal path
     * (quote creation); update is used for non-lifecycle fields (e.g. setting
     * {@code invoice_id} once the provider returns it).
     *
     * @param quote the durable view to persist
     * @return the persisted quote (timestamps populated)
     */
    MintQuote save(MintQuote quote);

    /**
     * Atomically transitions {@code lifecycle_state} from {@code expected} to
     * {@code target}. Implemented as a conditional UPDATE so concurrent writers
     * cannot both succeed.
     *
     * @param quoteId  the quote primary key
     * @param expected the state the caller observed
     * @param target   the state the caller wants to set
     * @return 1 if the transition happened, 0 if another writer advanced the state first
     */
    int casLifecycle(String quoteId, LifecycleState expected, LifecycleState target);
}
