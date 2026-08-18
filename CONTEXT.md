# Cashu Mint

A Cashu ecash mint: it issues blind-signed tokens against Lightning payments, redeems them, and must at all times be able to account for what it owes.

## Language

**Voucher**: a gift-card-style token issued against a recorded funding source rather than a customer Lightning payment. Always singular in identifiers and metric names.
_Avoid_: Vouchers (as a prefix), gift card, coupon

**Orphan Issuance**: a voucher proof that cannot be traced back to a funding row. The mint has issued value it has no record of being owed.
_Avoid_: Unfunded voucher, dangling issuance

**Stuck Payment**: a melt saga left in `PAYMENT_UNKNOWN` past its TTL — the Lightning payment may have left, the proofs are not burned, and by design nothing resolves it automatically. Only a human can settle it.
_Avoid_: Failed payment, pending melt, hung payment
