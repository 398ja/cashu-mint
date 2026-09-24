-- 398ja/cashu-mint#469 — name the one refusal the mint can diagnose exactly.
--
-- QuoteStatusUpdater refuses a webhook whose amount is null or non-positive:
--
--     if (notification.getAmount() == null || notification.getAmount() <= 0) {
--         log.warn("webhook_event invalid_amount ...");
--         incrementCounter(Outcome.amount_mismatch);
--
-- The LOG says invalid_amount. The OUTCOME says amount_mismatch, because no
-- other value existed. Those are different conditions with different remedies:
--
--   * amount_mismatch — a real amount that disagrees with the quote. Money may
--                       have moved for the wrong figure, so it is a
--                       reconciliation question.
--   * invalid_amount  — no amount at all. Nothing could have been charged, so
--                       it is a bug in whatever raised the invoice.
--
-- The conflation had a measured cost. On 2026-09-23 a fee that floored to zero
-- produced nine zero-amount invoices, and the dashboards showed 9962
-- amount_mismatch events. Reading that label, the first diagnosis was a unit or
-- scale disagreement between the adapter and the mint. It was not: the amount
-- was ZERO, which the log had said all along and the metric had not. That cost
-- a day, and it is the reason this is worth a migration rather than a comment.
--
-- Appending to the CHECK is safe for existing data: no row can already hold a
-- value the enum did not have.
--
-- It is NOT free on the Java side. PaymentWebhookController switches
-- exhaustively over Outcome, so the compiler refuses the build until the new
-- value has a mapping. That is the behaviour worth having: a new refusal with
-- no HTTP answer is a silent 500, and the compiler catching it is cheaper than
-- a caller discovering it.
ALTER TABLE webhook_event DROP CONSTRAINT IF EXISTS webhook_event_outcome_chk;

ALTER TABLE webhook_event ADD CONSTRAINT webhook_event_outcome_chk CHECK (outcome IN (
    'accepted',
    'amount_mismatch',
    'invalid_amount',
    'unit_mismatch',
    'method_mismatch',
    'duplicate',
    'tamper',
    'unsigned_rejected',
    'signature_invalid',
    'expired',
    'noop',
    'orphan'
));

-- Historical rows are NOT rewritten. The nine zero-amount events from
-- 2026-09-23 stay labelled amount_mismatch, because that is what the mint
-- recorded at the time and an audit table that edits its own history is worth
-- less than one with an awkward row in it.
