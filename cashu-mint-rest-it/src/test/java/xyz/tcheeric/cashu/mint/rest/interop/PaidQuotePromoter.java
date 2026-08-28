package xyz.tcheeric.cashu.mint.rest.interop;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;

/**
 * Stands in for the payment webhook that promotes a mint quote
 * {@code UNPAID → PAID} once Lightning settles.
 *
 * <p>The interop test drives a real wallet against a mint whose Lightning is
 * the dummy adapter: the adapter reports every invoice paid, but nothing
 * delivers that news to the durable quote row, and {@code MintTask} reads the
 * row rather than the adapter. Without this the wallet is refused with
 * {@code issuance_in_progress}, which says nothing about interoperability.
 *
 * <p>Polling rather than a one-shot update because the wallet chooses when to
 * request its quote, and the test must not race it. The interval is far shorter
 * than a wallet's quote-to-mint gap so the promotion is never the bottleneck.
 */
final class PaidQuotePromoter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PaidQuotePromoter.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final ScheduledExecutorService scheduler;

    private PaidQuotePromoter(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    static PaidQuotePromoter started(MintQuoteJpaRepository quotes) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                () -> promoteUnpaidQuotes(quotes),
                0,
                POLL_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        return new PaidQuotePromoter(scheduler);
    }

    private static void promoteUnpaidQuotes(MintQuoteJpaRepository quotes) {
        try {
            for (MintQuoteEntity quote : quotes.findAll()) {
                if (quote.getLifecycleState() == LifecycleState.UNPAID
                        && quotes.casLifecycle(quote.getQuoteId(),
                                LifecycleState.UNPAID, LifecycleState.PAID) == 1) {
                    log.info("paid_quote_promoter promoted quote_id={}", quote.getQuoteId());
                }
            }
        } catch (RuntimeException transientFailure) {
            log.debug("paid_quote_promoter poll_failed reason={}", transientFailure.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
