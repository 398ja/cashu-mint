package xyz.tcheeric.cashu.mint.jpa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Attaches funding to voucher quotes that were paid for but never funded
 * (issue #459).
 *
 * <p>A customer payment lands as a durable {@code webhook_event} with
 * {@code outcome='accepted'}. Attaching the funding row that turns it into a
 * redeemable voucher used to happen only when a client sent a mint request,
 * so the two were <em>coincidentally</em> linked rather than causally: a
 * 1000 EUR sale auto-split into five parts paid within two seconds exhausted
 * the client's 60s polling budget after two, and the remaining three sat
 * {@code UNFUNDED} with the money taken. Sixty-two such rows had accumulated
 * before anyone noticed.
 *
 * <p>{@code QuoteStatusUpdater} now attaches funding in the webhook
 * transaction, which closes that window. This sweep is the safety net behind
 * it, and it is the half that makes the guarantee real: half A is one code
 * path that must be correct, while this catches anything that bypasses or
 * breaks it — a bug, a rollback, a manual edit, or a future payment provider
 * wired straight to the event table. The grace period exists so half A gets
 * first claim and only genuine misses are swept.
 *
 * <p><strong>It sweeps toward funding, not away from it</strong>, unlike
 * {@link MeltSagaReconciler}, which fails a stale melt and releases its
 * proofs. The direction follows from which side of the irreversible step the
 * flow is stranded on: the customer's money has already been taken, so
 * completing the obligation is the conservative action and abandoning it is
 * the one that loses value. That reasoning mirrors {@link SwapHoldReconciler}'s
 * commit direction.
 *
 * <p><strong>It cannot issue the voucher.</strong> This sweep drives
 * {@code UNFUNDED → FUNDED} and stops. Signing requires the client's blinded
 * outputs, which the mint never persists and does not hold until it is asked,
 * so {@code FUNDED → ISSUING → ISSUED} stays with {@code MintTask}. What this
 * changes is that the value is now durably backed and waiting: a returning
 * client — or the gateway's own {@code PurchaseRecoveryJob} retry — completes
 * it, where before there was nothing to return to.
 *
 * <p><strong>Multi-replica behaviour.</strong> Unlike the gateway's recovery
 * jobs, this sweep carries no ShedLock, matching {@link MeltSagaReconciler} and
 * {@link SwapHoldReconciler} and the single-instance assumption
 * {@link InvariantGaugePoller} documents. It is safe under concurrency anyway,
 * which is worth stating because it is a property of the design rather than of
 * the deployment: two replicas sweeping the same quote both resolve the same
 * funding row — {@code VoucherFundingResolverImpl} keys the lazy insert on
 * {@code (provider, provider_event_id)} and re-reads the winner on conflict —
 * and {@code attachFundingAndAdvance} is a CAS, so exactly one wins and the
 * loser logs {@code race_lost}. The cost of a second replica is a duplicated
 * metric increment, not a duplicated funding row.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class VoucherFundingReconciler {

    private static final int DEFAULT_BATCH_SIZE = 200;

    private final VoucherQuoteJpaRepository voucherQuotes;
    private final VoucherFundingResolver fundingResolver;
    private final Duration gracePeriod;
    private final int batchSize;

    public VoucherFundingReconciler(
            @Autowired(required = false) VoucherQuoteJpaRepository voucherQuotes,
            @Autowired(required = false) VoucherFundingResolver fundingResolver,
            @Value("${cashu.mint.voucher.funding-grace-period:PT2M}") Duration gracePeriod,
            @Value("${cashu.mint.voucher.funding-reconcile-batch-size:200}") int batchSize) {
        this.voucherQuotes = voucherQuotes;
        this.fundingResolver = fundingResolver;
        this.gracePeriod = gracePeriod;
        // A non-positive batch size would make the sweep a silent no-op, which is
        // the one failure this class must never have: it would look healthy,
        // log nothing, and quietly stop being a safety net. Refuse the value
        // rather than honour it.
        if (batchSize <= 0) {
            log.warn("voucher_funding_reconcile invalid_batch_size={} — falling back to {}",
                    batchSize, DEFAULT_BATCH_SIZE);
            this.batchSize = DEFAULT_BATCH_SIZE;
        } else {
            this.batchSize = batchSize;
        }
    }

    @Scheduled(fixedDelayString = "${cashu.mint.voucher.funding-reconcile-interval:PT60S}")
    public void reconcileTick() {
        if (voucherQuotes == null || fundingResolver == null) {
            log.debug("voucher_funding_reconcile_skipped reason=no_voucher_dependencies");
            return;
        }
        try {
            sweepPaidButUnfunded();
        } catch (RuntimeException e) {
            // One bad tick must not stop the schedule: the next sweep is the recovery.
            // Logged with the throwable: this is the sweep itself failing, and a
            // bare getMessage() is null for exceptions like NullPointerException,
            // which would leave "cause=null" as the only trace of a broken net.
            log.warn("voucher_funding_reconcile sweep_failed", e);
        }
    }

    void sweepPaidButUnfunded() {
        List<VoucherQuoteEntity> stranded =
                voucherQuotes.findPaidButUnfunded(Instant.now().minus(gracePeriod), batchSize);
        if (stranded.size() >= batchSize) {
            // The sweep takes the oldest rows first and bounds the batch, so a
            // quote that can never be resolved keeps its place at the front of
            // every tick. Enough of those and newer, recoverable quotes are
            // never even attempted. The gauge stays non-zero either way, so
            // this line is what distinguishes "working through a backlog" from
            // "stuck behind rows that will never clear".
            log.warn("voucher_funding_reconcile batch_full size={} — backlog exceeds one tick; "
                    + "check for repeated VOUCHER_FUNDING_UNRESOLVED quote_ids", batchSize);
        }
        for (VoucherQuoteEntity quote : stranded) {
            attachFunding(quote);
        }
    }

    /**
     * Drives one stranded quote through the same resolver the request path
     * uses, so the two cannot diverge in what counts as funded.
     *
     * <p>A quote whose funding will not resolve is left alone and logged. The
     * gauge is what makes that visible; guessing at a funding row for money
     * the mint cannot account for is the one action worse than waiting.
     */
    private void attachFunding(VoucherQuoteEntity quote) {
        try {
            VoucherFunding funding = fundingResolver.resolveForQuote(quote).orElse(null);
            if (funding == null) {
                log.error("[voucher-funding][alert] VOUCHER_FUNDING_UNRESOLVED quote_id={} "
                                + "— accepted payment with no resolvable funding row",
                        quote.getQuoteId());
                MetricRecorders.voucher().fundingReconciled(false);
                return;
            }
            int attached = voucherQuotes.attachFundingAndAdvance(quote.getQuoteId(), funding.fundingId());
            if (attached == 0) {
                // A concurrent webhook or client mint attached first. The CAS
                // holding is the point; nothing to recover.
                log.info("voucher_funding_reconcile race_lost quote_id={}", quote.getQuoteId());
                return;
            }
            log.warn("voucher_funding_reconcile recovered quote_id={} funding_id={} "
                            + "reason=paid_but_never_funded",
                    quote.getQuoteId(), funding.fundingId());
            MetricRecorders.voucher().fundingReconciled(true);
        } catch (RuntimeException e) {
            log.error("[voucher-funding][alert] VOUCHER_FUNDING_RECONCILE_FAILED quote_id={}",
                    quote.getQuoteId(), e);
            MetricRecorders.voucher().fundingReconciled(false);
        }
    }
}
