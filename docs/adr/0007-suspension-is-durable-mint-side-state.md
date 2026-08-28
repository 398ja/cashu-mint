# Suspension is durable mint-side state, not a command the mint obeys

A suspended mint must stop issuing new tokens while continuing to honour swaps
and melts (ADR-0006). We decided the mint holds that state itself, durably, and
consults it on the issuance path — rather than exposing an operator endpoint
that flips an in-memory switch.

## Considered options

An operator endpoint on the mint was the conventional shape and would have given
the caller an immediate, unambiguous answer. It was rejected because the state
has to survive a restart: a mint that comes back up issuing again, because the
suspension lived only in memory or only in the caller's record, is precisely the
failure an operator suspends to prevent. Durable state answers that by
construction, and the check sits next to the archived-keyset check
([ADR-0004](./0004-archived-keysets-must-refuse-to-sign.md)) so both reasons a
mint may refuse to issue are enforced in one place.

It also keeps the mint authoritative about its own behaviour
([ADR-0006](./0006-suspend-drains-rather-than-stops.md)): the admin's record of a
suspension is a belief, and the mint's row is the fact.

## Consequences

Suspension is enforced on issuance only. Swap and melt continue to work, so a
suspended mint still serves redemption traffic and still needs its Lightning
gateway; it is not a way to take the mint offline.

How an operator's request reaches that state is deliberately left open here. The
admin and the mint share only the vault, and the vault has no field for it —
`MintEntity` carries nothing beyond the identifiers and the archived flag — so
the write path needs either a new vault field or a direct channel. That is a
separate decision, and the mint-side half stands on its own: without it there is
nothing for any channel to set.
