package xyz.tcheeric.cashu.mint.jpa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.repository.BlindSignatureJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.BlindSignatureJpaRepository.KeysetIssuedAmount;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIssuanceJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import org.springframework.beans.factory.ObjectProvider;
import xyz.tcheeric.cashu.mint.proto.metrics.InvariantMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issue #344 / ADR 0002 — exports operational invariants as gauges derived
 * from the database, by running the operator queries recorded on the
 * repositories on a fixed schedule.
 *
 * <p>Invariants are <em>durations in database state</em>, not in-process
 * moments: a counter incremented on a CAS transition can neither express
 * "stuck for an hour" nor survive a restart with its standing count intact,
 * which is exactly the wrong failure mode for conditions that by design never
 * resolve themselves. A gauge re-derived from operator SQL survives restarts
 * and reduces the alert to a one-line threshold.
 *
 * <p>Metric names are declared on {@code InvariantMetricsRecorder} in the
 * observability module, not here: this module owns the <em>query</em>, not the
 * catalogue (issue #343).
 *
 * <p>Currently exports:
 * <ul>
 *   <li>{@code cashu_mint_melt_stuck_payment_unknown} — melt sagas parked in
 *       {@code PAYMENT_UNKNOWN} past {@code payment-unknown-ttl}
 *       ({@link MeltSagaJpaRepository#countStuckPaymentUnknown(Instant)}).
 *       Non-zero means the Lightning payment may have left the mint while the
 *       proofs were never burned; nothing resolves it without a human.</li>
 *   <li>{@code cashu_mint_melt_payment_sent_burn_failed} — sagas whose
 *       payment settled while their proofs stayed spendable
 *       ({@link MeltSagaJpaRepository#countPaymentSentBurnFailed()}). Direct
 *       loss; there is no benign instance of this.</li>
 *   <li>{@code cashu_mint_voucher_orphan_issuance} — issued voucher quotes
 *       with no funding row
 *       ({@link VoucherIssuanceJpaRepository#countOrphanIssuance()}). The mint
 *       has issued value it cannot trace to what backs it.</li>
 *   <li>{@code cashu_mint_voucher_paid_unfunded} — voucher quotes still
 *       {@code UNFUNDED} despite an accepted payment event
 *       ({@link VoucherQuoteJpaRepository#countPaidUnfunded()}). The mint has
 *       taken money it has not issued against; issue #459.</li>
 *   <li>{@code cashu_mint_quote_paid_unissued} — mint quotes stuck in
 *       {@code PAID} past the stranded TTL
 *       ({@link MintQuoteJpaRepository#countPaidUnissued(Instant)}). Issue #460,
 *       and the only invariant here with no reconciler behind it: issuing needs
 *       the client's blinded outputs, so nothing can resolve these without the
 *       client returning. The gauge is the whole mechanism, not a check on
 *       one.</li>
 *   <li>{@code cashu_mint_issued_amount_total{keyset}}: total face value
 *       signed per keyset
 *       ({@link BlindSignatureJpaRepository#sumIssuedAmountByKeyset()}), the
 *       "issued" side of the issued-versus-backed reconciliation (issue #491).
 *       One series per keyset, bound the first time a poll sees the keyset.</li>
 *   <li>{@code cashu_mint_invariant_poll_failures_total} — polls that threw.
 *       Without it a failing query would park the gauge on a stale zero and
 *       silently disarm the alert; the companion alert rule watches this
 *       counter and the absence of the gauge series.</li>
 * </ul>
 *
 * <p><strong>Single-instance assumption.</strong> Every mint replica running
 * this poller would publish its own copy of each series, so two replicas
 * produce two conflicting series per gauge. Both compose files deploy exactly
 * one mint instance; running more would require leader election here. Do not
 * design around replicas until that changes.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class InvariantGaugePoller {

    private final MeltSagaJpaRepository meltSagas;
    private final VoucherIssuanceJpaRepository voucherIssuances;
    private final VoucherQuoteJpaRepository voucherQuotes;
    private final MintQuoteJpaRepository mintQuotes;
    private final BlindSignatureJpaRepository blindSignatures;
    private final Duration paymentUnknownTtl;
    private final Duration paidUnissuedTtl;
    private final InvariantMetricsRecorder recorder;
    /** How long a terminal saga may go unsettled before it counts (#464). */
    private static final Duration SETTLE_GRACE = Duration.ofMinutes(5);

    private final AtomicLong stuckPaymentUnknown = new AtomicLong();
    private final AtomicLong paymentSentBurnFailed = new AtomicLong();
    private final AtomicLong orphanIssuance = new AtomicLong();
    private final AtomicLong paidUnfunded = new AtomicLong();
    private final AtomicLong unfundedWithoutWebhook = new AtomicLong();
    private final AtomicLong unfundedRejectedOnly = new AtomicLong();
    private final AtomicLong terminalUnsettled = new AtomicLong();
    private final AtomicLong paidUnissued = new AtomicLong();
    private final Map<String, AtomicLong> issuedAmountByKeyset = new ConcurrentHashMap<>();

    /**
     * The recorder is injected rather than read off {@link MetricRecorders}
     * because the gauge is <em>bound</em> in this constructor: taking it as a
     * dependency makes Spring create the observability bean first, where
     * reading the static could bind to the no-op if this poller happened to be
     * constructed earlier. The static fallback covers contexts with no
     * observability module at all.
     */
    public InvariantGaugePoller(MeltSagaJpaRepository meltSagas,
                                VoucherIssuanceJpaRepository voucherIssuances,
                                VoucherQuoteJpaRepository voucherQuotes,
                                MintQuoteJpaRepository mintQuotes,
                                BlindSignatureJpaRepository blindSignatures,
                                ObjectProvider<InvariantMetricsRecorder> recorderProvider,
                                @Value("${cashu.mint.melt.payment-unknown-ttl:PT1H}") Duration paymentUnknownTtl,
                                @Value("${cashu.mint.quote.paid-unissued-ttl:PT1H}") Duration paidUnissuedTtl) {
        this.meltSagas = meltSagas;
        this.voucherIssuances = voucherIssuances;
        this.voucherQuotes = voucherQuotes;
        this.mintQuotes = mintQuotes;
        this.blindSignatures = blindSignatures;
        this.paymentUnknownTtl = paymentUnknownTtl;
        this.paidUnissuedTtl = paidUnissuedTtl;
        this.recorder = recorderProvider.getIfAvailable(MetricRecorders::invariant);
        // Bound eagerly so the series is scrapeable before the first poll: a
        // meter that only materialises once something breaks is
        // indistinguishable from a broken exporter on a dashboard.
        recorder.bindStuckPaymentUnknown(stuckPaymentUnknown::get);
        recorder.bindPaymentSentBurnFailed(paymentSentBurnFailed::get);
        recorder.bindOrphanIssuance(orphanIssuance::get);
        recorder.bindPaidUnfunded(paidUnfunded::get);
        recorder.bindUnfundedWithoutWebhook(unfundedWithoutWebhook::get);
        recorder.bindUnfundedRejectedOnly(unfundedRejectedOnly::get);
        recorder.bindTerminalUnsettled(terminalUnsettled::get);
        recorder.bindPaidUnissued(paidUnissued::get);
    }

    @Scheduled(fixedDelayString = "${cashu.mint.invariant.poll-interval:PT60S}")
    public void pollTick() {
        poll("stuck_payment_unknown", stuckPaymentUnknown,
                () -> meltSagas.countStuckPaymentUnknown(Instant.now().minus(paymentUnknownTtl)));
        poll("payment_sent_burn_failed", paymentSentBurnFailed, meltSagas::countPaymentSentBurnFailed);
        poll("orphan_issuance", orphanIssuance, voucherIssuances::countOrphanIssuance);
        poll("paid_unfunded", paidUnfunded, voucherQuotes::countPaidUnfunded);
        poll("unfunded_without_webhook", unfundedWithoutWebhook,
                voucherQuotes::countUnfundedWithoutWebhook);
        poll("unfunded_rejected_only", unfundedRejectedOnly,
                voucherQuotes::countUnfundedRejectedOnly);
        // Grace period so a settle still in flight does not read as a failure.
        poll("terminal_unsettled", terminalUnsettled,
                () -> meltSagas.countTerminalWithUnsettledProofs(
                        Instant.now().minus(SETTLE_GRACE)));
        poll("paid_unissued", paidUnissued,
                () -> mintQuotes.countPaidUnissued(Instant.now().minus(paidUnissuedTtl)));
        pollIssuedAmounts();
    }

    /**
     * Refreshes the issued amount of every keyset that has signed anything.
     *
     * <p>A keyset's series is bound the first time it appears, since keysets are created at
     * runtime and cannot be bound up front like the other gauges. A failed query keeps every
     * last value, for the same reason {@link #poll} does.
     */
    private void pollIssuedAmounts() {
        try {
            for (KeysetIssuedAmount issued : blindSignatures.sumIssuedAmountByKeyset()) {
                issuedAmountGauge(issued.getKeysetId()).set(issued.getIssuedAmount());
            }
        } catch (RuntimeException e) {
            recorder.pollFailed();
            log.warn("invariant_poll issued_amount_failed cause={}", e.getMessage());
        }
    }

    private AtomicLong issuedAmountGauge(String keysetId) {
        return issuedAmountByKeyset.computeIfAbsent(keysetId, id -> {
            AtomicLong gauge = new AtomicLong();
            recorder.bindIssuedAmount(id, gauge::get);
            return gauge;
        });
    }

    /**
     * Runs one invariant query into its gauge. Each is polled independently so
     * one failing query cannot stop the others from refreshing.
     *
     * @param name  invariant name, for the failure log line
     * @param gauge holder the bound gauge reads
     * @param query the operator query
     */
    private void poll(String name, AtomicLong gauge, java.util.function.LongSupplier query) {
        try {
            gauge.set(query.getAsLong());
        } catch (RuntimeException e) {
            // Hold the last known value rather than reporting a false zero —
            // a zero here would silently clear a firing alert. The counter is
            // what makes the staleness itself alertable.
            recorder.pollFailed();
            log.warn("invariant_poll {}_failed cause={}", name, e.getMessage());
        }
    }
}
