# Payment webhook architecture

This explanation describes how payment processing changed in version 0.8.0 with the introduction of webhook-based notifications.

## Background

Prior to version 0.8.0, the mint used synchronous polling to check payment status. Every mint request triggered a gateway query, adding latency to user-facing requests and creating inefficiencies when the same quote was checked repeatedly.

## Webhook-based flow

Version 0.8.0 introduces a push-based model where payment gateways send notifications to the mint when payments are received. The new `cashu-mint-webhook` module handles these notifications.

### Architecture comparison

**Before (polling-based):**
```
POST /v1/mint
    ↓
MintTask.execute()
    ↓
Gateway.checkPaymentStatus(quoteId)  ← blocks for gateway latency
    ↓
Return result
```

**After (webhook-based):**
```
Payment Gateway                          Cashu Mint
    │                                        │
Payment received ─────────→ POST /webhook/payment
                                   │
                          Signature validation
                                   │
                          QuoteStatusUpdater
                          (cache updated instantly)

Later...

POST /v1/mint
    ↓
MintTask.execute()
    ↓
PaymentStatusChecker.isPaid()  ← instant cache lookup
    ↓ (fallback to polling if not cached)
Return result
```

## Components

### PaymentWebhookController

Receives payment notifications at `/webhook/payment`. Validates HMAC signatures from the `X-Webhook-Signature` header and deduplicates using `X-Idempotency-Key`.

### QuoteStatusUpdater

Maintains an in-memory cache of paid quotes using Caffeine with TTL-based eviction to prevent unbounded memory growth. Implements the `PaymentStatusChecker` interface to provide instant lookups from the protocol layer.

The cache automatically evicts entries after configurable TTLs:
- **Paid quotes**: Evicted after 1 hour (configurable) if not consumed
- **Idempotency keys**: Evicted after 24 hours (configurable) to prevent reprocessing old notifications

For high-availability deployments, consider using Redis for cross-instance state sharing.

### PaymentStatusChecker

A new interface in `cashu-mint-protocol` that abstracts payment status checks:

```java
public interface PaymentStatusChecker {
    boolean isPaid(String quoteId);
    Optional<String> getPreimage(String quoteId);
    default void markConsumed(String quoteId) { }
}
```

The `MintTask` checks this interface first, falling back to gateway polling if the quote is not in the webhook cache.

## Configuration

```properties
# HMAC secret for webhook signature validation (REQUIRED in production)
webhook.secret=${MINT_WEBHOOK_SECRET:}

# Cache TTL settings
webhook.cache.quote-ttl=1h           # How long paid quotes stay in cache
webhook.cache.idempotency-ttl=24h    # How long idempotency keys are remembered
webhook.cache.max-quotes=10000       # Maximum cached paid quotes
webhook.cache.max-idempotency-keys=100000  # Maximum idempotency keys

# Gateway client timeouts (optimized for virtual threads)
gateway.client.connect-timeout=5s
gateway.client.read-timeout=30s
```

When `webhook.secret` is not configured, signature validation is skipped (development mode only). In production, always set `MINT_WEBHOOK_SECRET` to prevent unsigned webhook acceptance.

## Benefits

- **Reduced latency**: Mint requests return instantly when payment is cached
- **Lower gateway load**: No repeated queries for the same quote
- **Real-time awareness**: Payments are known as soon as the gateway reports them
- **Backward compatible**: Falls back to polling if webhooks are unavailable

## See also

- [Configure gateways](../how-to/configure-gateways.md)
- [Virtual thread issues runbook](../runbooks/virtual-thread-issues.md)
- [Architecture overview](architecture-overview.md)
