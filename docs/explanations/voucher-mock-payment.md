# Voucher Mock Payment and Free Splitting

This document explains how voucher tokens differ from regular Cashu tokens in payment verification and denomination handling.

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

**Voucher Split (100 units -> 33 + 67):**
```
1. Input proof: 100 units (single proof, arbitrary amount)
2. Want: 33 + 67
3. Direct swap: Create two new proofs for 33 and 67
4. No denomination constraints - done in one step
```

### Dynamic Key Derivation

Vouchers use HMAC-SHA256 based key derivation to support arbitrary amounts:

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

This allows the mint to sign any positive amount without pre-registering keys in a keyset.

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
INFO mint_task voucher_quote amount=100 arbitrary_denomination=true
```

## Security Considerations

1. **Quote Registry**: Only quotes registered via `VoucherQuoteRegistry.storeFaceValue()` are treated as vouchers
2. **Amount Validation**: Voucher mint amounts must match the registered face value
3. **Type Isolation**: Mixed voucher/regular operations are rejected to prevent type confusion
4. **Master Secret**: The voucher master secret must be kept secure and consistent across restarts

## Related Documentation

- [Representing vouchers as structured secrets](voucher-structured-secrets.md)
- [Voucher mint quotes as percentage fees](voucher-mint-quote-percentage.md)
- [Configuration reference](../reference/configuration.md)
