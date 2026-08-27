# Operators authenticate with their own credential; roles are resolved server-side

**Superseded (issue #373).** Operators now authenticate with a NAP handshake over
their Nostr key and carry a session cookie; the per-operator credential, its reset
workflow and the shared bootstrap token are gone. The decision below stands only in
its second half — roles are still resolved server-side from the operator store.

Access control on the admin API read the caller's roles from an `X-Admin-Roles`
request header, behind a single shared static token — so any token holder could
assert any role, and the operator store was never consulted at the enforcement
point. We decided each Operator gets their own credential, and the filter
resolves their roles from the operator store that already exists. The
`X-Admin-Roles` header is removed from the server, the web client and the tests.

## Considered options

Deleting roles altogether and keeping one shared admin token was cheaper, and
defensible for a small operator team. We rejected it because it makes the Audit
Trail unattributable — it would record whichever identity the caller claimed —
and attributability is the main thing the admin currently does well. Adopting
Spring Security was the other option, rejected as a large dependency for a
service with a single kind of user when the operator store had to become the
source of truth either way.

## Consequences

The shared token survived here as a bootstrap credential. Issue #373 removed it
along with the per-operator credential: the Super Administrator is configured by
npub, so an empty operator store is no longer a reason to keep a second way in.

The audit actor is the authenticated Operator. No request field or header offers
one, so there is nothing for a caller to claim — an Audit Trail that stores a
claimed identity is evidence of nothing.
