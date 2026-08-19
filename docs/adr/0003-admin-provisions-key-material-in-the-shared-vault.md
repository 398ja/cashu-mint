# The admin provisions mint key material in the shared vault

cashu-mint-admin does not talk to the mint over HTTP. It writes the mint's
entity, keyset and key entries directly into the vault that the mint reads at
boot, driven by an outbox saga with deterministic ids, idempotency on 409,
bounded retry and compensation. Shared state is the integration point between
the two.

This is recorded because it is not the obvious shape and a reader will wonder
why an administrative service holds vault credentials and generates keys. The
alternative — the admin commanding the mint over an operator API, with the mint
generating its own keys — keeps key custody in one place and would be the
conventional choice. It was not taken, and reversing to it now would mean
rebuilding a working provisioning saga against a mint API that does not exist.

## Consequences

Rotation should follow this path rather than introduce a second one: write the
new keyset to the vault and archive the previous, as provisioning and retirement
already do. That is only safe once
[ADR-0004](./0004-archived-keysets-must-refuse-to-sign.md) is in place.
