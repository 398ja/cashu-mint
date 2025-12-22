# Voucher Mint Quote Percentage Fees

This note summarizes how the mint handles voucher mint quotes when pricing is expressed as a percentage of the voucher face value. It complements the more detailed implementation plan kept under `project/` and focuses on what integrators need to know.

## What it does
- For voucher mints, the mint can charge `floor(face_value * fee_percent / 100)`.
- Percentage is configurable via properties, environment variables, or system properties (see `VoucherFeeConfig`).
- Non-voucher mints are unaffected; they continue to charge the full amount.

## Where it runs
- Calculation occurs inside `MintQuoteTask` before delegating to the payment gateway.
- Applies only when the secret type is a voucher and fee config is present.

## Key config knobs
- `voucher.quote.fee-percent` (properties)
- `VOUCHER_QUOTE_FEE_PERCENT` (env)
- `-Dvoucher.quote.fee-percent` (JVM arg)

## Testing expectations
- Unit: `VoucherMintQuoteTaskTest` exercises small/large amounts and zero-fee scenarios.
- Integration: voucher mint quote flows should return the calculated price while preserving NUT-04 response structure.
