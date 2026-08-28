# Cashu Mint

A Cashu ecash mint: it issues blind-signed tokens against Lightning payments, redeems them, and must at all times be able to account for what it owes.

## Language

**Voucher**: a gift-card-style token issued against a recorded funding source rather than a customer Lightning payment. Always singular in identifiers and metric names.
_Avoid_: Vouchers (as a prefix), gift card, coupon

**Orphan Issuance**: a voucher proof that cannot be traced back to a funding row. The mint has issued value it has no record of being owed.
_Avoid_: Unfunded voucher, dangling issuance

**Stuck Payment**: a melt saga left in `PAYMENT_UNKNOWN` past its TTL — the Lightning payment may have left, the proofs are not burned, and by design nothing resolves it automatically. Only a human can settle it.
_Avoid_: Failed payment, pending melt, hung payment

**Operator**: The human who administers a Mint through cashu-mint-admin. Holds
their own credential, so every Audit Trail entry names a person rather than a
shared identity.
_Avoid_: Admin, user, actor

**Archived Keyset**: A keyset the Mint no longer signs with, but still verifies
and redeems, indefinitely. Keysets are never deleted — deleting one strands
every token it signed.
_Avoid_: Inactive keyset, retired keyset, old keyset

**Rotation**: Replacing a Mint's active keyset with a newly generated one, the
previous becoming an Archived Keyset. Provisioning already writes key material
to the shared vault; rotation is the same act applied to a Mint that already has
keys.
_Avoid_: Key change, re-key, cycle

**Keyset Drift**: Disagreement between the keysets the admin wrote to the
shared vault and the keysets the Mint advertises on `/v1/keysets`. Not
observable today — the admin reads only the vault — and naming it keeps the
gap visible rather than assumed shut.
_Avoid_: Out of sync, mismatch, stale keyset

**Actuate**: To cause a change outside the admin's own tables — in the shared
vault, or in the Mint's behaviour. The counterpart is to *record*. A command
that records without actuating has not been carried out.
_Avoid_: Apply, execute, effect
