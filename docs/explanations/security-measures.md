# Security Measures

This document describes the security mechanisms implemented in cashu-mint to protect against common attack vectors in ecash systems.

## Overview

The mint implements defense-in-depth security across multiple layers:

| Layer | Mechanisms |
|-------|------------|
| Cryptographic | BDHKE verification, DLEQ proofs, Schnorr signatures |
| Concurrency | Quote locking, proof locking, atomic operations |
| Input Validation | Amount matching, proof state checks, spending conditions |
| Network | Webhook signature validation, WebSocket CORS |
| Operational | Idempotency handling, secure logging, request tracing |

---

## Double-Spend Prevention

### The Problem

Double-spend attacks are the primary threat to ecash systems. An attacker attempts to spend the same token multiple times before the mint can record it as spent.

**Attack vectors:**
1. **Race condition exploit**: Submit identical swap/melt requests simultaneously
2. **Double-mint attack**: Submit mint requests for the same paid quote concurrently
3. **Replay attack**: Resubmit previously successful requests

### Quote Locking (Double-Mint Prevention)

**File:** `cashu-mint-protocol/.../util/QuoteLockManager.java`

When a Lightning invoice is paid, multiple concurrent requests could attempt to mint tokens for the same quote. Without protection, both could pass the "is paid?" check before either marks tokens as issued.

**Solution:** Per-quote locking with `ReentrantLock`:

```java
public static QuoteLock lockQuote(String quoteId) {
    Lock lock = quoteLocks.computeIfAbsent(quoteId,
        k -> new LockEntry(new ReentrantLock())).lock();
    lock.lock();
    return new QuoteLock(quoteId, lock);
}
```

**Guarantees:**
- Only one thread processes a quote at a time
- Payment check and token issuance are atomic
- Reference counting enables automatic cleanup
- Metrics track lock contention (`cashu_mint_lock_wait_seconds`)

**Usage in MintTask:**
```java
try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
    if (!gateway.checkPaymentStatus(quoteId)) {
        throw new CashuErrorException("quote_not_paid");
    }
    // Sign blinded messages and persist signatures
}
```

### Proof Locking (Swap/Melt Serialization)

**File:** `cashu-mint-protocol/.../util/ProofLockManager.java`

Concurrent swap or melt requests using the same proofs must be serialized to prevent double-spending.

**Solution:** Per-secret locking with deadlock prevention:

```java
public static ProofLock lockSecrets(List<String> secrets) {
    // Sort secrets to prevent deadlock (consistent lock ordering)
    TreeSet<String> sortedSecrets = new TreeSet<>(secrets);
    List<Lock> acquiredLocks = new ArrayList<>();

    for (String secret : sortedSecrets) {
        Lock lock = secretLocks.computeIfAbsent(secret,
            k -> new LockEntry(new ReentrantLock())).lock();
        lock.lock();
        acquiredLocks.add(lock);
    }
    return new ProofLock(sortedSecrets, acquiredLocks);
}
```

**Key design decisions:**
- **Sorted locking order**: Prevents deadlock when multiple operations lock overlapping proof sets
- **All-or-nothing**: Acquires all locks before proceeding
- **Reference counting**: Cleans up locks when no threads are waiting

**Usage in SwapTask:**
```java
try (ProofLockManager.ProofLock ignored = ProofLockManager.lockSecrets(
        proofs.stream().map(p -> p.getSecret().toString()).toList())) {
    verifyProofsTask.execute();  // Validates proofs aren't spent
    hold.claim(proofs);              // Durable exclusive hold on the inputs
    signOutputs();                   // Issues new tokens
    hold.commit();                   // Held inputs move to SPENT in one transition
}
```

The lock is per process. The durable guarantee is the vault hold, described in
[Spent proofs are only ever moved forward](#spent-proofs-are-only-ever-moved-forward).

---

## Cryptographic Verification

### BDHKE (Blind Diffie-Hellman Key Exchange)

**File:** `cashu-mint-protocol/.../tasks/validator/RSSSpendingCondition.java`

The core Cashu protocol uses BDHKE for blind signatures. When verifying a proof:

```java
public void validate(Proof<T> proof, PrivateKey privateKey) throws CashuErrorException {
    ECPoint C = proof.getC();
    String secret = proof.getSecret().toString();

    if (!BDHKEUtils.verify(secret, privateKey.toBytes(), C)) {
        throw new CashuErrorException("verify_proof_failed_error");
    }
}
```

**What this verifies:**
1. The proof's unblinded signature `C` is valid
2. The signature was created by the mint's private key
3. The secret wasn't tampered with

### DLEQ Proofs (NUT-12)

**File:** `cashu-mint-protocol/.../proto/service/DLEQProofGenerator.java`

DLEQ (Discrete Logarithm Equality) proofs demonstrate that the same private key was used for both the mint's public key and the blind signature—without revealing the private key.

```java
public DLEQProof generate(BlindedMessage blindedMessage, BlindSignature signature,
                          PrivateKey privateKey, PublicKey publicKey) {
    ECPoint B_ = blindedMessage.getB_();
    ECPoint C_ = signature.getC_();

    return DLEQUtils.generateProof(privateKey.toBytes(), B_, C_, publicKey.toBytes());
}
```

**Benefits:**
- Clients can verify signature correctness offline
- Detects if mint uses different keys for signing
- Required for NUT-12 compliance

### Schnorr Signatures (P2PK Spending)

**File:** `cashu-mint-protocol/.../tasks/validator/P2PKSpendingCondition.java`

Pay-to-Public-Key (P2PK) proofs require Schnorr signature verification:

```java
private boolean verifySchnorrSignature(String pubkeyHex, String signatureHex,
                                       byte[] messageHash) {
    ECPoint pubkey = // decode from hex
    BigInteger r = // first 32 bytes of signature
    BigInteger s = // last 32 bytes of signature

    // Schnorr verification: s*G == R + e*P
    ECPoint R = // compute from r
    BigInteger e = new BigInteger(1,
        sha256(concat(R.getEncoded(), pubkey.getEncoded(), messageHash)));

    return G.multiply(s).equals(R.add(pubkey.multiply(e)));
}
```

**Supports:**
- Single-signature spending
- Multisig (n-of-m threshold)
- Refund keys with locktime
- SIG_ALL and SIG_INPUTS flags

---

## Input Validation

### Proof Verification Pipeline

**File:** `cashu-mint-protocol/.../tasks/VerifyProofsTask.java`

Every proof undergoes multi-stage validation:

```
┌─────────────────┐
│ Parse Proof     │ → Decode secret, extract keyset ID
└────────┬────────┘
         ↓
┌─────────────────┐
│ Check Keyset    │ → Verify keyset exists and is active
└────────┬────────┘
         ↓
┌─────────────────┐
│ Check State     │ → Verify proof not already spent (via vault)
└────────┬────────┘
         ↓
┌─────────────────┐
│ Detect Type     │ → RSS, P2PK, HTLC, or Voucher
└────────┬────────┘
         ↓
┌─────────────────┐
│ Spending Cond.  │ → Type-specific cryptographic verification
└─────────────────┘
```

### Amount Validation

For swaps, input and output amounts must match exactly:

```java
long totalInput = inputs.stream().mapToLong(Proof::getAmount).sum();
long totalOutput = outputs.stream().mapToLong(BlindedMessage::getAmount).sum();

if (totalOutput != totalInput - fees) {
    throw new CashuErrorException("amount_mismatch_error");
}
```

For melts, input proofs must cover the Lightning invoice amount plus fee reserve:

```java
long required = invoiceAmount + feeReserve + mintFees;
if (totalInput < required) {
    throw new CashuErrorException("insufficient_inputs_error");
}
```

### Voucher Type Isolation

**File:** `cashu-mint-protocol/.../tasks/SwapTask.java`

Voucher proofs cannot be mixed with regular proofs in the same operation:

```java
boolean hasVoucher = inputs.stream().anyMatch(VoucherSecretDetector::isVoucherSecret);
boolean hasRegular = inputs.stream().anyMatch(p -> !VoucherSecretDetector.isVoucherSecret(p));

if (hasVoucher && hasRegular) {
    throw new CashuErrorException("mixed_proof_types_error");
}
```

**Rationale:** Vouchers follow Model B economics (merchant IOUs) and should not be fungible with real Bitcoin-backed tokens.

---

## Webhook Security

### HMAC-SHA256 Signature Validation

**File:** `cashu-mint-webhook/.../WebhookSignatureValidator.java`

Payment gateway webhooks are authenticated using HMAC-SHA256:

```java
public boolean isValid(String signature, PaymentNotification payload) {
    if (webhookSecret == null || webhookSecret.isBlank()) {
        return true;  // Dev mode: no validation
    }

    String payloadJson = objectMapper.writeValueAsString(payload);
    String expectedSignature = computeHmacSha256(payloadJson, webhookSecret);

    // Constant-time comparison prevents timing attacks
    return MessageDigest.isEqual(
        signature.getBytes(StandardCharsets.UTF_8),
        expectedSignature.getBytes(StandardCharsets.UTF_8)
    );
}
```

**Security properties:**
- **Authenticity**: Only the gateway with the shared secret can produce valid signatures
- **Integrity**: Payload tampering invalidates the signature
- **Timing-safe**: `MessageDigest.isEqual()` prevents timing side-channel attacks

**Configuration:**
```properties
webhook.secret=${WEBHOOK_SECRET:}  # Required in production
```

### Idempotency Keys

**File:** `cashu-mint-webhook/.../QuoteStatusUpdater.java`

Duplicate webhook deliveries are deduplicated using idempotency keys:

```java
private final Cache<String, Boolean> processedNotifications = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofHours(24))
    .maximumSize(100_000)
    .build();

public boolean processNotification(PaymentNotification notification) {
    String idempotencyKey = notification.getIdempotencyKey();

    Boolean existing = processedNotifications.asMap()
        .putIfAbsent(idempotencyKey, Boolean.TRUE);

    if (existing != null) {
        log.debug("Duplicate notification ignored: {}", idempotencyKey);
        return false;
    }
    // Process notification...
}
```

---

## WebSocket Security

### CORS Origin Restriction

**File:** `cashu-mint-rest/.../config/WebSocketConfig.java`

WebSocket connections are restricted to trusted origins:

```java
@Override
public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(nut17WebSocketHandler, "/v1/ws")
        .setAllowedOrigins(allowedOrigins.split("\\s*,\\s*"));
}
```

**Configuration:**
```properties
# Development (default - NOT for production!)
cashu.websocket.allowed-origins=*

# Production
cashu.websocket.allowed-origins=https://wallet.example.com,https://app.example.com
```

**Security warnings logged:**
```java
if ("*".equals(allowedOrigins.trim()) && !isDevelopmentProfile()) {
    log.warn("SECURITY WARNING: WebSocket allowed-origins is set to '*'. " +
             "Restrict to specific domains in production.");
}
```

### Message Size Limits

```java
@Override
public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration.setMessageSizeLimit(64 * 1024);  // 64 KB max
    registration.setSendBufferSizeLimit(64 * 1024);
    registration.setSendTimeLimit(10_000);  // 10 second timeout
}
```

---

## Operational Security

### Secure Logging

**File:** `cashu-vault-api/.../db/log/SecretLogId.java` (used by the mint's vault client)

Proof secrets are never logged in plaintext. Instead, they're hashed for correlation:

```java
// SecretLogId.of: a truncated SHA-256, never the value itself
byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8));
return "sha256:" + hex(digest, 16);  // first 16 hex chars

// Usage
log.debug("Retrieving ProofEntity by secret {}", SecretLogId.of(secret));
```

**Benefits:**
- Logs can be shared safely for debugging
- Correlation still possible via hash prefix
- Protects against log exfiltration attacks

### Request Tracing

**File:** `cashu-mint-rest/.../controller/CashuController.java`

All requests are traceable via correlation IDs:

```java
public static final String REQUEST_ID_HEADER = "X-Request-ID";

@PostMapping("/swap")
public ResponseEntity<PostSwapResponse> swap(@RequestBody PostSwapRequest request,
                                              HttpServletRequest httpRequest) {
    String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
    if (requestId == null) {
        requestId = UUID.randomUUID().toString();
    }

    log.info("swap_request request_id={} input_count={} output_count={}",
             requestId, request.getInputs().size(), request.getOutputs().size());
    // ...
}
```

**Benefits:**
- Trace requests across distributed systems
- Correlate security incidents
- Debug duplicate/replay attempts

### Spent proofs are only ever moved forward

**Files:** `cashu-mint-protocol/.../tasks/SwapProofHold.java`, `MeltTask.java`,
`service/ProofVaultService.java`

cashu-vault is the mint's only record of spent proofs, so the one property that
must hold is that a `SPENT` row never becomes spendable again (cashu-mint#492,
cashu-vault#154).

Both value-moving flows spend their inputs through a **hold**:

1. `insertOrClaimForHold` claims every input as `PENDING` under one hold id. An
   input that is already `SPENT`, or held by another flow, is not claimed, and the
   flow fails closed before it signs (swap) or pays (melt).
2. `commitSpentForHold` moves exactly the held rows to `SPENT`. A commit that
   reports fewer rows spent than were held is a failure, not a smaller success:
   the swap strands the hold for the operator, and the melt lands in
   `PAYMENT_SENT_BURN_FAILED`.

`ProofVaultService` deliberately has no method that writes a whole proof row.
The melt used to burn its inputs by storing each row and re-posting it with its
state changed, which only worked while the vault would overwrite an existing
row, and a row that can be overwritten is a row whose `SPENT` state can be
undone. The vault now refuses both the overwrite and the delete, and in
PostgreSQL a trigger refuses to undo a spend underneath the API.

Hold rows are keyed where the vault already records each proof
(`storageKeyFor`), so a proof recorded under the legacy NUT-00 point is claimed
under that point and cannot slip in as a fresh row. See
[Spending a proof exactly once across the secret encoding change](spent-proof-key-encoding.md).

`SpentProofCannotBeRevivedIT` runs this end to end against the real vault
server: it spends a proof, tries to delete and overwrite the row with the mint's
own token, and presents the proof again. Pointed at cashu-vault 0.14.0 it fails
at the delete, which is the attack it exists to catch.

---

## Lock Observability

### Metrics

**File:** `cashu-mint-protocol/.../metrics/LockMetricsRecorder.java`

Lock contention is monitored via Prometheus metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `cashu_mint_lock_wait_seconds` | Histogram | Time waiting to acquire lock |
| `cashu_mint_lock_hold_seconds` | Histogram | Time holding lock |
| `cashu_mint_lock_active` | Gauge | Currently held locks |

**Labels:** `type` (quote/proof), `resource_id` (first 8 chars of ID)

**Alerting thresholds (suggested):**
- Lock wait > 1s: Warning
- Lock wait > 5s: Critical
- Active locks > 100: Investigation needed

---

## Threat Model Summary

| Threat | Mitigation | Effectiveness |
|--------|------------|---------------|
| Double-spend (concurrent requests) | Proof/quote locking | Strong |
| Double-mint (race on payment check) | Quote locking | Strong |
| Forged proofs | BDHKE verification | Cryptographically secure |
| Malformed blind signatures | DLEQ proofs | Cryptographically secure |
| Webhook spoofing | HMAC-SHA256 signatures | Strong (with secret) |
| Cross-origin WebSocket | CORS allowlist | Effective |
| Log credential leakage | Secret hashing | Effective |
| Replay attacks | Idempotency keys | Effective (24h window) |
| Voucher/token mixing | Type isolation | Enforced |

---

## Configuration Checklist

Production deployments should verify:

- [ ] `webhook.secret` is set to a strong random value
- [ ] `cashu.websocket.allowed-origins` lists only trusted domains
- [ ] Reverse proxy rate limiting is configured
- [ ] Lock metrics are monitored and alerted
- [ ] Logs are retained for security incident investigation
- [ ] Request IDs are propagated through the full stack

---

## See Also

- [Payment webhook architecture](payment-webhook-architecture.md)
- [Virtual thread issues runbook](../runbooks/virtual-thread-issues.md)
- [Configuration reference](../reference/configuration.md)
