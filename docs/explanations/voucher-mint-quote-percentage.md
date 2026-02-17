# Voucher mint quotes as percentage fees

## TL;DR

For voucher mints, the mint charges `floor(face_value * fee_percent / 100)` instead of the full face value. The percentage is configurable via `voucher.quote.fee-percent` (properties), `VOUCHER_QUOTE_FEE_PERCENT` (env), or `-Dvoucher.quote.fee-percent` (JVM arg). The calculation occurs inside `MintQuoteTask` before delegating to the payment gateway and applies only when the secret type is a voucher and fee config is present. Non-voucher mints are unaffected. Unit tests in `VoucherMintQuoteTaskTest` exercise small/large amounts and zero-fee scenarios; integration tests verify the calculated price preserves the NUT-04 response structure.

---

This document describes the planned change to calculate voucher mint quotes as a configurable percentage of the voucher face value. No code has been updated yet; this explains the intended behavior, configuration surface, and edge cases before implementation.

## Goals
- Charge voucher minting as a percentage fee instead of a flat or mirrored face-value price.
- Make the percentage configurable via properties, system properties, and environment variables.
- Keep the Cashu NUT-04 mint flow and response shape intact while adjusting the amount handed to the payment gateway.

## Calculation model
- **Formula:** `voucher_price = floor(voucher_amount * fee_percentage / 100)`.
- **Rounding:** Floor to the nearest sat to avoid overcharging when amounts are small; zero fee is allowed when either amount or percentage is zero.
- **Example (percent literal):** With `fee_percentage=10`, `voucher_amount=1_000` produces `voucher_price=100`.
- **Example (matching the 1_000 → 10 sat scenario):** Set `fee_percentage=1` if the desired fee is 1% (10 sats on 1,000). The user-facing percentage must match the business rule you intend; the configuration is interpreted as an actual percent.
- **Validation:** Reject negative amounts or percentages; optionally cap the percentage at 100 to prevent accidental markups greater than the face value unless explicitly allowed.

## Configuration surface

| Property | Default | Env override | System property | Notes |
| --- | --- | --- | --- | --- |
| `voucher_quote_fee_percent` | `10` | `VOUCHER_QUOTE_FEE_PERCENT` | `-Dvoucher_quote_fee_percent=…` | Percent value applied to voucher mint quotes. |

- Defaults can live in the existing configuration file (e.g., `proto.properties`).
- Environment variables take precedence over property files; system properties can be used for per-process overrides.
- Validation should occur at startup: reject missing/invalid values and log the effective percent.

### Example overrides
- Environment: `export VOUCHER_QUOTE_FEE_PERCENT=1` (makes a 1,000 sat voucher cost 10 sats).
- System property: `java -Dvoucher_quote_fee_percent=15 -jar cashu-mint-rest.jar` (15% fee).

## Flow impact (NUT-04)
- **Request:** `POST /v1/mint/quote/{method}` continues to accept the voucher face value in the `amount` field.
- **Quote creation:** Before calling `Gateway.createMintQuote`, compute `voucher_price` using the configured percentage and pass that value to the gateway so the invoice/request reflects the fee-based price.
- **Quote status:** `GET /v1/mint/quote/{method}/{quote_id}` remains the same; it should expose the paid status of the fee-based invoice.
- **Minting:** `POST /v1/mint/{method}` still mints proofs totaling the face value (`amount` in the original quote). Ensure downstream validation uses the quote’s face value, not the charged fee, so wallets receive the full voucher amount once the fee invoice is paid.
- **Non-voucher mints:** Apply the percentage model only to voucher mint quotes; leave other payment methods or asset types unchanged unless explicitly configured to share the fee model.

## Edge cases and safeguards
- Reject requests where `amount <= 0`.
- Guard against integer overflow for very large voucher amounts; prefer `long`/`BigInteger` internally for the multiplication step.
- Document that `fee_percentage > 100` is unsupported unless business rules require surcharges.
- Ensure the paid amount tracked in the gateway matches the computed `voucher_price` to avoid double-charging or under-collection.

## Rollout and backward compatibility
- Default of `10` preserves a 10% fee; adjust the default if you need to match the 1,000 → 10 sat example (set default to `1` instead).
- Keep API schemas unchanged; only the quoted `request`/invoice amount differs.
- Add metrics/logs that emit both `face_value` and `fee_percentage` to validate production behavior.
- Update client-facing docs to clarify that voucher minting incurs a percentage fee and how to configure it.

## Testing checklist
- Unit: percentage computation (0%, 1%, 10%, 100%), rounding behavior, overflow guard, and config parsing/validation for env/system properties.
- Integration: `POST /v1/mint/quote/{method}` returns an invoice whose amount matches the computed fee; `GET /v1/mint/quote/{method}/{quote_id}` reflects paid status of that invoice; `POST /v1/mint/{method}` mints the full face value after paying only the fee.
- Edge: extremely small amounts (ensure zero/low fees behave as expected), very large amounts, invalid percentages, and non-voucher mint paths unaffected.

## See Also

- [Voucher mock payment and free splitting](voucher-mock-payment.md)
- [Voucher structured secrets](voucher-structured-secrets.md)
- [REST API reference](../reference/rest-api.md)
