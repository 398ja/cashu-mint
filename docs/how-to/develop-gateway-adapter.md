# Develop a Custom Gateway Adapter

This guide explains how to implement a custom payment gateway adapter for cashu-mint. A gateway adapter bridges the mint protocol to a specific payment backend (e.g., a Lightning node, on-chain wallet, or hosted payment service).

## The Gateway Interface

All gateway adapters implement the `Gateway` interface from the `payment-adapter-common` module. The interface defines the following methods:

```java
public interface Gateway {

    // -- Mint quote lifecycle --
    String createMintQuote(Integer amount, String description);

    // -- Melt quote lifecycle --
    String createMeltQuote(Integer amount, String request, String description);
    String createMeltQuote(String request);

    // -- Payment operations --
    String pay(String request);
    boolean checkPaymentStatus(String request);
    String getPaymentPreimage(String request);

    // -- Quote metadata --
    String getRequest(String quoteId);
    Integer getAmount(String quoteId);
    Integer getPaymentExpiry(String quoteId);
    Integer getFeeReserve(String request);

    // -- Identity --
    String getName();
    PaymentType getPaymentType();
    String getGatewayId();

    default boolean supports(PaymentMethod method) { ... }
}
```

## Method Contract

### `createMintQuote(Integer amount, String description)`
Create an invoice or payment request for the given amount. Return a quote ID that can be used to check status and retrieve the payment request.

### `createMeltQuote(Integer amount, String request, String description)`
Create a melt quote for paying an external invoice. The `request` parameter is the invoice/payment request string. Return a quote ID.

### `checkPaymentStatus(String request)`
Return `true` if the payment identified by the request has been settled. Throw `InvoiceNotPaidException` if the quote is unknown.

### `pay(String request)`
Execute an outgoing payment for the given request. Return the payment preimage on success.

### `getFeeReserve(String request)`
Return the estimated fee in the smallest unit (e.g., sats) for settling the given request. The mint reserves this amount from the melting wallet.

### `getGatewayId()`
Return a stable, unique identifier for this gateway (e.g., `"phoenixd"`, `"lnd"`). Used in logging and metrics.

### `getPaymentType()`
Return the `PaymentType` enum value (e.g., `PaymentType.LIGHTNING_NETWORK`).

## Step-by-Step Implementation

### 1. Create a Maven Module

Create a new module under the `payment-adapter` project:

```
payment-adapter-ln/
  payment-adapter-ln-mygateway/
    pom.xml
    src/main/java/xyz/tcheeric/payment/adapter/ln/mygateway/
      MyGateway.java
```

Add the `payment-adapter-common` dependency:

```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>payment-adapter-common</artifactId>
    <version>${payment-adapter.version}</version>
</dependency>
```

### 2. Implement the Gateway

```java
@Supports({PaymentMethod.BOLT11})
public class MyGateway implements Gateway {

    @Override
    public String createMintQuote(Integer amount, String description) {
        // Call your backend to create an invoice for `amount` sats
        // Return a unique quote ID
    }

    @Override
    public boolean checkPaymentStatus(String request) {
        // Check if the invoice has been paid
        // Return true if paid, false if pending
    }

    @Override
    public String pay(String request) {
        // Pay the given Lightning invoice
        // Return the payment preimage
    }

    // ... implement remaining methods
}
```

Use the `@Supports` annotation to declare which payment methods your gateway handles.

### 3. Register the Gateway

Configure the mint to use your gateway via environment variables:

```bash
GATEWAY_BOLT11_SAT=xyz.tcheeric.payment.adapter.ln.mygateway.MyGateway
```

The naming convention is `GATEWAY_{METHOD}_{UNIT}`. The mint resolves the class at startup and instantiates it.

See [Configure gateways](configure-gateways.md) for all configuration options.

### 4. Add the Dependency to cashu-mint-rest

Include your gateway module in the mint's runtime classpath:

```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>payment-adapter-ln-mygateway</artifactId>
    <version>${project.version}</version>
    <scope>runtime</scope>
</dependency>
```

## Testing

### Use DummyGateway as Reference

The `DummyGateway` in `payment-adapter-ln-dummy` is a complete in-memory implementation useful as both a reference and a test double:

- Auto-pays mint quotes immediately (simulates instant Lightning payment).
- Stores quotes in a `ConcurrentHashMap` (no external dependencies).
- Reads defaults from `dummy.properties` on the classpath.
- Returns `PaymentType.LIGHTNING_NETWORK` and supports BOLT11, BOLT12, and ON_CHAIN methods.

### Write Unit Tests

Test each method independently:

```java
@Test
void createMintQuote_returnsValidQuoteId() {
    MyGateway gateway = new MyGateway(/* config */);
    String quoteId = gateway.createMintQuote(1000, "test");
    assertNotNull(quoteId);
    assertFalse(quoteId.isBlank());
}

@Test
void checkPaymentStatus_returnsFalseWhenUnpaid() {
    MyGateway gateway = new MyGateway(/* config */);
    String quoteId = gateway.createMintQuote(1000, "test");
    assertFalse(gateway.checkPaymentStatus(quoteId));
}
```

### Integration Testing

Run the mint with your gateway against the full stack to verify end-to-end mint and melt flows:

```bash
GATEWAY_BOLT11_SAT=xyz.tcheeric.payment.adapter.ln.mygateway.MyGateway \
  mvn clean verify -Pintegration-tests
```

## See Also

- [Configure gateways](configure-gateways.md)
- [Architecture overview](../explanations/architecture-overview.md)
- [Glossary](../reference/glossary.md)
