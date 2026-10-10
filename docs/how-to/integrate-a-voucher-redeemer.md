# Integrate a redeemer with the voucher redemption cap (cashu-mint#540)

This is for the services that **credit** a coupon: gateway-core (payment requests), gateway-customer (receive) and wallet-api. They tell the mint about each credit, and the mint holds the credits for one coupon to the sats issued for it.

## The contract

1. **Declare only credits that will be final.** A declaration is made on the `POST /v1/swap` that takes the coupon into the redeemer's own proofs:

   ```
   X-Cashu-Voucher-Redemption: <voucher_id>:<sats kept>
   X-Cashu-Voucher-Redeemer:   <this service's token>
   ```

   - `<sats kept>` is the voucher sats this swap takes, minus any V change returned to the payer. It is not a figure converted back from the credited cents: a per-part `ceil` drifts up by one sat per part and refuses the last honest part.
   - One coupon per swap. A swap whose inputs span more than one voucher id is refused in enforce mode.
   - Do not declare undeclared hops: splits, change, a holder cashing V into plain sats.

2. **Keep the receipt.** A declared swap that the mint counted answers with
   `X-Cashu-Voucher-Redemption-Receipt: <64 hex>`. That is the swap's NUT-19 request fingerprint. Store it with the credit. A NUT-19 replay of the same request returns the same receipt.

3. **Release the declaration when the credit is undone:** a refund, a reversal, or a downstream failure after the swap (DB write failed, payment request expired, face claim refused). Otherwise the declaration keeps consuming the coupon's cap, and an attacker can use you as a free oracle: relabel plain sats onto someone else's coupon, get it credited, get refunded, and the real holder is refused.

   ```
   POST /v1/voucher-redemptions/release
   X-Cashu-Voucher-Redeemer: <this service's token>
   {"voucher_id": "<id>", "request_fingerprint": "<receipt>"}
   ```

   | Status | `outcome` | Meaning |
   |---|---|---|
   | 200 | `released` | The declaration was given back. |
   | 200 | `already_released` | Given back before. Releases are idempotent: repeat freely. |
   | 400 | `not_the_declared_sats` | `sats` was sent and differs from what the swap declared. A release is the whole declaration, never more, never part. |
   | 401 | | No trusted redeemer token. |
   | 403 | `other_redeemer` | Another service declared that swap. |
   | 404 | `not_found` / `other_voucher` | No declared swap under that receipt for that voucher. |

4. **Retry with the same request.** If a declared swap's reply is lost, retry the identical body (NUT-19). The mint reuses the claim it already recorded for that request, so the retry is never counted twice and never refused for the first attempt's claim.

5. **Map `90025` to the holder.** See the design's holder-facing contract: "this coupon has already been redeemed in full", offer "swap back to sats" (the holder's proofs are untouched), and route support by the voucher id prefix in the alert.

## Modes

| `cashu.mint.voucher.redemption-cap` | A good declaration | Malformed, untrusted or not matching the inputs | Over the cap |
|---|---|---|---|
| `off` | headers not read | headers not read | not counted |
| `log` (default) | recorded | logged, counted as `outcome="ignored"`, **swap proceeds** | accepted, `overspent`, alert |
| `enforce` | recorded | swap refused (401 / 90025) | swap refused (90025) |

`enforce` refuses to boot without the durable store, without a token, or unless `cashu.mint.voucher.issuer-binding=enforce`. The cap is read from the coupon's signed terms, and those only bind when the signer is bound.

## Token handling

- **One token per service** (`CASHU_MINT_VOUCHER_REDEEMER_TOKENS=core,customer,wallet-api` secrets). A declaration and its release are bound to the token that made them: a leaked token reaches only that service's credits, and the logs say which service declared (first 8 hex of the token's SHA-256).
- **Never logged by the mint.** It compares SHA-256 digests in constant time and stores only the digest on the claim. `VoucherRedemptionCapLogModeIT.theRedeemerTokenNeverReachesTheLogs` pins this.
- **Redact the header at the edge and in every client.** Anything on the path that logs request headers sees a bearer secret: the edge proxy access log, APM (Glowroot), and debug HTTP logging in the gateways' mint client. Redact `X-Cashu-Voucher-Redeemer` in each. Keep the token in the secret store, not in compose files.
- **Rotation:** add the new token to the mint first (the list takes both), switch the service, then drop the old one. In `log` a mismatch during rotation is ignored and counted, never an outage.
- **The structural fix** (review §1.3, R1) is a declaration signed by the issuing stall's key over `voucher_id:sats:request-fingerprint`. It removes the shared secret. It is a follow-up.
