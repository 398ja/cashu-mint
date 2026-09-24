package xyz.tcheeric.cashu.mint.proto.metrics;

import java.util.function.Supplier;

/**
 * Typed recorder port for the operational-invariant gauges — see
 * {@code docs/adr/0002-db-derived-gauges-for-operational-invariants.md} and
 * issue #343.
 *
 * <p>Unlike the counter recorders, the values here are <em>bound</em> rather
 * than incremented: an invariant is a duration in database state, re-derived
 * by a poller, so the meter reads a supplier instead of accumulating events.
 * Binding is one-shot at poller construction; the poller then just updates the
 * value the supplier reads.
 *
 * <p>This exists so the poller in {@code cashu-mint-jpa} never touches a
 * {@code MeterRegistry}: the metric names stay declared in the observability
 * module with every other name, which is what makes the catalogue checkable
 * (#347).
 */
public interface InvariantMetricsRecorder {

    /**
     * Binds the Stuck Payment gauge to {@code value}. Emits
     * {@code cashu_mint_melt_stuck_payment_unknown}: melt sagas parked in
     * {@code PAYMENT_UNKNOWN} past the configured TTL.
     *
     * @param value supplier read on every scrape
     */
    void bindStuckPaymentUnknown(Supplier<Number> value);

    /**
     * Binds the burn-failure gauge to {@code value}. Emits
     * {@code cashu_mint_melt_payment_sent_burn_failed}: sagas whose payment
     * settled while their proofs stayed spendable. Direct loss with no benign
     * instance, so the alert on it has no sustain period.
     *
     * @param value supplier read on every scrape
     */
    void bindPaymentSentBurnFailed(Supplier<Number> value);

    /**
     * Binds the Orphan Issuance gauge to {@code value}. Emits
     * {@code cashu_mint_voucher_orphan_issuance}: issued voucher quotes with
     * no funding row — value issued with no record of what backs it.
     *
     * @param value supplier read on every scrape
     */
    void bindOrphanIssuance(Supplier<Number> value);

    /**
     * Binds the Paid-Unfunded gauge to {@code value}. Emits
     * {@code cashu_mint_voucher_paid_unfunded}: voucher quotes still
     * {@code UNFUNDED} despite an accepted payment event (issue #459).
     *
     * <p>Non-zero means the mint has taken money it has not issued against.
     * There is no benign instance of this; the alert carries a short sustain
     * period only so a quote caught mid-reconcile does not page anyone.
     *
     * @param value supplier read on every scrape
     */
    void bindPaidUnfunded(Supplier<Number> value);

    /**
     * Binds the Unfunded-Without-Webhook gauge to {@code value}. Emits
     * {@code cashu_mint_voucher_unfunded_without_webhook}: voucher quotes still
     * {@code UNFUNDED} for which the mint holds no payment event whatsoever
     * (issues #459 and #462).
     *
     * <p>This is the complement of {@link #bindPaidUnfunded}, and exists
     * because that gauge's correctness creates a blind spot. Paid-Unfunded
     * requires an {@code accepted} webhook before it counts a row, since inside
     * the mint that event is the only evidence money moved. A payment the mint
     * was never told about therefore raises neither gauge, and the #459
     * reconciler skips it for the same reason — correctly, because minting
     * against an unrecorded payment is precisely the failure being guarded.
     *
     * <p>Non-zero does not mean the mint mishandled anything. It means the mint
     * and the payment adapter disagree, and the mint cannot tell from its own
     * data which side is right. The response is the adapter-side cross-check
     * and re-delivery (#462), never a local fix-up.
     *
     * @param value supplier read on every scrape
     */
    void bindUnfundedWithoutWebhook(Supplier<Number> value);

    /**
     * Binds the Unfunded-Rejected-Only gauge to {@code value}. Emits
     * {@code cashu_mint_voucher_unfunded_rejected_only}: voucher quotes still
     * {@code UNFUNDED} whose only payment events were rejected (#459, #462).
     *
     * <p>The third of three, and the one that completes the partition.
     * {@code outcome} has twelve values, so "has an accepted event" and "has
     * no event" do not cover the population between them: a quote whose only
     * events were {@code invalid_amount}, {@code amount_mismatch} or
     * {@code tamper} raises neither.
     *
     * <p>The query behind this keys on {@code outcome = 'accepted'} rather
     * than listing the rejections, so a new outcome is counted here without
     * being added anywhere. That is deliberate: enumerating refusals would
     * mean a refusal introduced later silently left the partition.
     *
     * <p>Unlike its two siblings this one is unambiguous about whether the
     * mint was told. It was, and it refused. What the customer is owed depends
     * on which outcome it was, which is why this reports rather than resolves.
     *
     * @param value supplier read on every scrape
     */
    void bindUnfundedRejectedOnly(Supplier<Number> value);

    /**
     * Binds the Terminal-Unsettled gauge to {@code value}. Emits
     * {@code cashu_mint_melt_terminal_unsettled}: melt sagas that reached a
     * terminal state through the reconciler and never recorded that their
     * proofs were settled (issue #464).
     *
     * <p>The reconciler records the transition, then calls the vault. A failed
     * vault call is logged and execution continues, and the CAS has already
     * moved the saga out of the state the sweep selects on — so nothing
     * retries, and the customer's proofs stay PENDING with no process that
     * will free them.
     *
     * <p>Non-zero is an operator condition rather than something to automate.
     * The remedy is to replay the settle for that hold, which is safe because
     * both vault operations are idempotent conditional updates, but deciding
     * to do so needs a human looking at why the first attempt failed.
     *
     * @param value supplier read on every scrape
     */
    void bindTerminalUnsettled(Supplier<Number> value);

    /**
     * Binds the Paid-Unissued gauge to {@code value}. Emits
     * {@code cashu_mint_quote_paid_unissued}: mint quotes in {@code PAID} past
     * the stranded TTL (issue #460).
     *
     * <p>Sibling to {@link #bindPaidUnfunded}, and the one invariant here with
     * no reconciler behind it: issuing needs the client's blinded outputs, so
     * nothing can resolve these without the client returning. The gauge is the
     * whole mechanism rather than a check on one, which is why its absence
     * matters more than most.
     *
     * @param value supplier read on every scrape
     */
    void bindPaidUnissued(Supplier<Number> value);

    /**
     * An invariant poll threw. Emits
     * {@code cashu_mint_invariant_poll_failures_total} — without it a failing
     * poll would hold a stale gauge value and silently disarm the alert.
     */
    void pollFailed();
}
