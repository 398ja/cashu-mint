# Voucher Mock Payment and Free Splitting

This document explains how voucher tokens differ from regular Cashu tokens in payment verification and denomination handling.

> **STATUS (verified 2026-09-18):** the *mock payment* half of this document is
> live. The *arbitrary denomination* half is **not** — it is implemented but
> never enabled, so voucher proofs are minted on the same power-of-two keyset
> ladder (1…1024) as regular proofs. See the "Dynamic Key Derivation" section
> for the evidence. Sections describing free splitting are marked as design
> intent, not behaviour.

## Overview

Voucher tokens are merchant IOUs with no real bitcoin backing. Unlike regular Cashu tokens that require Lightning payment verification, vouchers use "mock payment" - the mint automatically accepts voucher quotes without checking for actual payment.

## Trust Model Comparison

| Token Type | Payment | Trust Model | Redemption |
|------------|---------|-------------|------------|
| **Voucher** | Mock (auto-accepted) | Merchant IOU | Merchant honors face value |
| **Regular Cashu** | Real Lightning | Cryptographic | Bitcoin payout via Lightning |

## Mock Payment Flow

When a client requests to mint tokens:

1. **Quote Detection**: `MintTask` checks if the quote ID is registered in `VoucherQuoteRegistry`
2. **Payment Verification**:
   - **Voucher Quote**: Skip `gateway.checkPaymentStatus()` - the backing is the merchant's commitment
   - **Regular Quote**: Require real Lightning payment per NUT-04
3. **Signature Generation**: Both flows proceed to sign blinded messages

```
Mint Request
    |
    v
MintTask.execute()
    |
    v
Check: VoucherQuoteRegistry.isVoucherQuote(quoteId)?
    |-- YES (Voucher) --> Skip payment check, auto-accept
    |-- NO (Regular)  --> Require real payment via gateway.checkPaymentStatus()
    |
    v
Sign blinded messages
    |
    v
Return proofs
```

## Implementation Details

### Payment Verification Skip

In `MintTask.java`:

```java
boolean isVoucherQuote = VoucherQuoteRegistry.isVoucherQuote(quoteId);

if (isVoucherQuote) {
    log.info("mint_task voucher_quote_detected quote_id={} mock_payment=true", quoteId);
    // Vouchers are merchant IOUs - skip payment verification
} else {
    // Regular tokens require real Lightning payment per NUT-04
    Gateway gateway = mintProtocolService.createGateway(method);
    boolean paid = gateway.checkPaymentStatus(quoteId);
    if (!paid) {
        throw new CashuErrorException("mint_invoice_not_paid_error");
    }
}
```

### Mixed Proof Type Validation

Swapping voucher proofs with regular proofs in the same operation is not allowed. `SwapTask` validates this before processing:

```java
boolean hasVoucherProofs = proofs.stream().anyMatch(this::isVoucherProof);
boolean hasRegularProofs = proofs.stream().anyMatch(proof -> !isVoucherProof(proof));

if (hasVoucherProofs && hasRegularProofs) {
    throw new CashuErrorException("mixed_proof_types_error",
        "Cannot mix voucher and regular proofs in same operation");
}
```

## Arbitrary Denominations (Free Splitting)

Since vouchers use mock payments with no real bitcoin backing, there is no reason to constrain voucher amounts to power-of-2 denominations.

### Denomination Comparison

| Aspect | Regular Cashu Tokens | Voucher Tokens |
|--------|---------------------|----------------|
| Payment | Real Lightning | Mock (auto-accepted) |
| Denominations | Power-of-2 (1, 2, 4, 8, 16...) | Arbitrary (any positive amount) |
| Splitting | Requires swap for redenomination | Free splitting at any boundary |
| Backing | Real sats | None (merchant IOU) |
| NUT Compliance | Required (NUT-00, NUT-01) | Not required |

### Split Flow Comparison

**Regular Cashu Token Split (100 sats -> 33 + 67):**
```
1. Input proofs: 64 + 32 + 4 = 100 sats
2. Want: 33 + 67
3. Problem: Can't partition existing proofs
4. Solution: Swap at mint to get: 32 + 1 + 64 + 2 + 1
5. Then partition: (32 + 1) = 33, (64 + 2 + 1) = 67
```

**Voucher Split (100 units -> 33 + 67), AS DESIGNED, NOT AS BUILT:**
```
1. Input proof: 100 units (single proof, arbitrary amount)
2. Want: 33 + 67
3. Direct swap: Create two new proofs for 33 and 67
4. No denomination constraints - done in one step
```

That is the intent. With `voucherMode` disabled (see below), a voucher today is
minted as a power-of-two ladder like any other proof: 100 becomes 64+32+4, and
1500 becomes 1024+256+128+64+16+8+4.

### Dynamic Key Derivation

> **NOT IN EFFECT.** Everything in this section describes a design that is
> implemented but **never switched on**. Verified 2026-09-18 in the source and
> against the running staging mint:
>
> - `SignBlindedMessageTask` has the `voucherMode` branch below, but every
>   construction site — `MintTask`, `SwapTask`, `MeltTask` — uses the four-argument
>   constructor, which hardcodes `voucherMode=false, voucherMasterSecret=null`.
>   Searching for `voucherMode` outside that one class returns nothing.
> - No `VOUCHER_MASTER_SECRET` is configured in any compose file or environment.
> - Staging logs show voucher mints fetching one vault key **per power-of-two
>   denomination** (`.../00e3372e61d05605/1024`, `/256`, ...), and `GET /v1/keys`
>   returns exactly eleven keys, 1 to 1024.
>
> In practice voucher signing therefore takes the `else` branch and resolves a
> key by **exact amount from the keyset**, exactly as a regular mint does. A
> proof of an off-ladder denomination such as 1500 would fail with
> `sign_private_key_not_found`.
>
> This matters beyond tidiness: the claim below was read as licence to shrink
> voucher tokens ~7x by issuing one large proof instead of a power-of-two ladder.
> That is not available today. Delivering it needs either a keyset rotation (a
> NUT-02 v2 keyset id hashes the keys, so adding denominations produces a new
> keyset id) or wiring up `voucherMode`, which would move voucher signing to
> HMAC-derived keys **outside the vault** — a key-custody decision rather than an
> optimisation. See
> `imani-deploy/docs/specs/cashu-token-size-per-proof-payload.md`.

The intended design: HMAC-SHA256 based key derivation to support arbitrary
amounts.

```java
// VoucherKeyDerivation.java
public static PrivateKey deriveKeyForAmount(byte[] masterSecret, long amount) {
    Mac hmac = Mac.getInstance("HmacSHA256");
    SecretKeySpec secretKey = new SecretKeySpec(masterSecret, "HmacSHA256");
    hmac.init(secretKey);
    String derivationInput = "voucher:" + amount;
    byte[] derivedKey = hmac.doFinal(derivationInput.getBytes(UTF_8));
    return PrivateKey.fromBytes(derivedKey);
}
```

This *would* allow the mint to sign any positive amount without pre-registering
keys in a keyset. Nothing reaches it.


## Configuration

The voucher master secret can be configured via:

1. **System property**: `-Dvoucher.master.secret=<hex>`
2. **Environment variable**: `VOUCHER_MASTER_SECRET=<hex>`
3. **Properties file**: `voucher.master.secret=<hex>` in `proto.properties`
4. **Auto-generation**: If not configured, a secure random secret is generated at startup

See `VoucherMasterSecretConfig` for implementation details.

## Audit Trail

All voucher quote detections are logged for audit purposes:

```
INFO mint_task voucher_quote_detected quote_id=abc123 mock_payment=true
INFO mint_task voucher_quote amount=100 output_validation=skipped
```

(The second line previously read `arbitrary_denomination=true`. It was renamed
because it reports a skipped `validateOutputs` call, not a signing capability,
and the old wording was read as the latter.)

## Security Considerations

1. **Quote Registry**: Only quotes registered via `VoucherQuoteRegistry.storeFaceValue()` are treated as vouchers
2. **Amount Validation**: Voucher mint amounts must match the registered face value
3. **Type Isolation**: Mixed voucher/regular operations are rejected to prevent type confusion
4. **Master Secret**: The voucher master secret must be kept secure and consistent across restarts

## Related Documentation

- [Representing vouchers as structured secrets](voucher-structured-secrets.md)
- [Voucher mint quotes as percentage fees](voucher-mint-quote-percentage.md)
- [Configuration reference](../reference/configuration.md)
