package xyz.tcheeric.cashu.mint.webhook;

import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent.Outcome;

/**
 * Result of {@link QuoteStatusUpdater#record} for a single incoming webhook
 * delivery. Wraps the resolved {@link Outcome} plus a flag indicating whether
 * the quote actually transitioned {@code PENDING → PAID} as a consequence.
 *
 * <p>Spec 001 FR-005 / FR-006 / FR-008.
 */
public record WebhookOutcome(Outcome outcome, boolean firstAccepted) {

    public static WebhookOutcome accepted() {
        return new WebhookOutcome(Outcome.accepted, true);
    }

    public static WebhookOutcome of(Outcome outcome) {
        return new WebhookOutcome(outcome, false);
    }

    public boolean isAccepted() {
        return outcome == Outcome.accepted;
    }
}
