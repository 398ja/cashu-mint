# A keyset's redemption cutoff is a dated, approved, final mint-side record

[ADR-0004](./0004-archived-keysets-must-refuse-to-sign.md) made an archived
keyset stop signing but go on redeeming indefinitely. That is the right default
and it stays the default. We decided that an Operator may end it for one archived
keyset, on a date announced in advance, through its own four-eyes approval, and
that once the date passes the decision is final.

## Why a keyset ever needs to stop redeeming

Indefinite redemption assumes the keyset's private keys are still secret. When
they may not be, as with keysets that were never derived from the mint's seed,
a forged proof is indistinguishable from a real one at redemption time.
Rotation stops new signatures on such a keyset but leaves every forged proof
spendable forever. Shortening the window during which those proofs redeem is the
only mitigation, and it costs honest holders who never swap off the keyset the
value they hold. That trade belongs to people, not to a default, which is why it
is a separate operation from archiving.

## What we decided

- **The record lives in the mint.** `keyset_redemption_cutoff` holds one
  `redeem_until` per keyset, and every change is appended to
  `keyset_redemption_cutoff_history`. Swap and melt read the repository
  directly on every request, not through the keyset cache, so refusal starts at
  the instant and not a cache TTL later. A refused input fails with
  `keyset_expired` (12003) and counts on
  `cashu_mint_keyset_redemption_refused_total{keyset,operation}`.
- **Only an archived keyset.** The mint refuses a cutoff on its signing keyset,
  and the swap and melt gate ignores a cutoff on the active keyset (and logs an
  error) rather than refusing the mint's own fresh proofs.
- **Announced, then enforced.** A cutoff must be in the future when set, and
  mint-admin requires at least 24 hours of notice. While it is pending,
  `/v1/keysets` advertises the date as NUT-02 `final_expiry` so wallets can swap
  off in time. Once it has passed, the keyset and its keys are no longer
  advertised at all.
- **A passed cutoff is final.** It cannot be moved or cleared. Reopening a
  keyset whose forged proofs were refused would revive them.
- **Four eyes in mint-admin.** One Operator requests, a different Operator
  approves, and only then does the outbox deliver it to the mint's
  `PUT /admin/keysets/{id}/redemption-cutoff`. A 4xx from the mint ends the
  request FAILED with the mint's reason. A 5xx or unreachable mint is retried.
  Every step lands on the admin audit timeline.

## Consequences

Holders who do not swap before the date lose that value. That is the point of
the operation, and why it needs notice, a second approver, and an audit trail.
The mint's guarantee under ADR-0004 now reads: an archived keyset redeems until
an Operator-approved cutoff, which it advertises before enforcing.
