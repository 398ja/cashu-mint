# Voucher Mint Quote Percentage Fee - Implementation Plan

This document provides a detailed implementation plan for the voucher mint quote percentage fee feature described in [voucher-mint-quote-percentage.md](voucher-mint-quote-percentage.md).

## Table of Contents

- [Overview](#overview)
- [Current State Analysis](#current-state-analysis)
- [Implementation Phases](#implementation-phases)
- [Critical Decision Points](#critical-decision-points)
- [Risk Assessment](#risk-assessment)
- [Testing Strategy](#testing-strategy)

## Overview

**Feature:** Calculate voucher mint quotes as a configurable percentage of the voucher face value instead of charging the full amount.

**Formula:** `voucher_price = floor(voucher_amount * fee_percentage / 100)`

**Key Principles:**
- Keep NUT-04 mint flow and response shape intact
- Apply percentage fee only to voucher mint quotes
- Make percentage configurable via properties, system properties, and environment variables
- Non-voucher mints remain unaffected

**Estimated Effort:** 11-17 days across 6 phases

---

## Current State Analysis

### 1. NUT-04 Mint Quote Implementation

**Primary Files:**
- `/cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NUT04.java`
- `/cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintQuoteTask.java`
- `/cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintQuoteStatusTask.java`

**Current Flow:**

```
1. POST /v1/mint/quote/{method} (CashuController:107-112)
   ↓
2. NUT04.quote(amount, method)
   ↓
3. MintQuoteTask.execute()
   ↓
4. gateway.createMintQuote(amount, null)  ← KEY INTEGRATION POINT
   ↓
5. Return PostMintQuoteResponse
```

**Where Fee Calculation Needed:**
- Location: `MintQuoteTask.execute()` at line 46, before calling `gateway.createMintQuote()`
- Calculate: `voucher_price = floor(amount * percentage / 100)`
- Pass calculated price to gateway instead of original amount

### 2. Configuration System

**Existing Pattern:** `FeeConfig.java` (lines 15-34)
- Loads from `proto.properties` via classpath resource
- Supports environment variable overrides
- Example: `cashu.melt.fee-reserve-percent=0.05` (5%)

**Loading Precedence:**
1. System property: `-Dproperty.name=value`
2. Environment variable: `PROPERTY_NAME`
3. Property file: `proto.properties`
4. Default value

**Implementation Approach:**
- Follow existing `FeeConfig` pattern
- Create `VoucherFeeConfig` utility class
- Add property: `voucher.quote.fee-percent=10`
- Support environment variable: `VOUCHER_QUOTE_FEE_PERCENT`
- Support system property: `-Dvoucher.quote.fee-percent=...`

### 3. Voucher Detection Mechanism

**Current Voucher Implementation:**
- Vouchers issued via separate endpoint: `POST /v1/vouchers` (VoucherController)
- NOT currently integrated with NUT-04 mint quote flow
- Voucher secrets detected via: `VoucherSecretDetector.isVoucherSecret(secret)` (reflection-based)
- Enabled via: `voucher.enabled=true` in application.properties

**Challenge:** How to identify voucher mint quotes at the NUT-04 level?
- Current `MintQuoteTask` only receives `amount` and `method`
- No information about whether request is for a voucher

**Options for Detection:**

| Option | Pros | Cons | Complexity |
|--------|------|------|------------|
| **A: Request Parameter** | Explicit, clean, no ambiguity | Requires cashu-lib API change | Medium |
| **B: Dedicated Endpoint** | No API changes, clear separation | Duplicates endpoint logic | Low |
| **C: Payment Method** | No API changes | Ambiguous, couples concepts | Low |

**Recommended:** Option B (dedicated endpoint) to avoid external dependency changes.

### 4. Gateway Integration

**Gateway Interface:**
- Abstract payment methods (Lightning, etc.)
- Key method: `Gateway.createMintQuote(amount, description)`
- Returns quote ID, payment request, and expiry
- Test implementation: `DummyGateway` for unit tests

**Current Gateway Call:**
```java
String quoteId = gateway.createMintQuote(amount, null);
```

**Modified Gateway Call:**
```java
double percentage = VoucherFeeConfig.getFeePercentage();
long voucherPrice = (long) Math.floor(amount * percentage / 100.0);
String quoteId = gateway.createMintQuote(voucherPrice, null);
```

### 5. Face Value Storage Challenge

**Problem:** When minting occurs later (`POST /v1/mint/{method}`), we need the original face value, not the fee price.

**Why:** The gateway invoice is for the fee amount, but the minted tokens must total the original face value.

**Solution Options:**

1. **Store in gateway metadata** - If gateway supports key-value metadata on quotes
2. **Internal mapping service** - Maintain `Map<String, Long>` of `quoteId → faceValue`
3. **Vault storage** - Add face_value column to quote records
4. **Quote entity extension** - Extend quote model with face_value field

**Recommended:** Internal mapping service for minimal invasiveness.

### 6. Key File Locations

**Protocol Layer:**
```
cashu-mint-protocol/
├── src/main/java/xyz/tcheeric/cashu/mint/proto/
│   ├── nut/NUT04.java                          ← Add quoteVoucher() method
│   ├── tasks/MintQuoteTask.java                ← Reference implementation
│   ├── tasks/VoucherMintQuoteTask.java         ← NEW: Voucher quote task
│   └── util/
│       ├── FeeConfig.java                      ← Pattern reference
│       ├── VoucherFeeConfig.java               ← NEW: Config loader
│       └── VoucherFeeCalculator.java           ← NEW: Fee math
├── src/main/resources/proto.properties         ← Add property
└── src/test/java/xyz/tcheeric/cashu/mint/proto/
    ├── tasks/MintQuoteTaskTest.java            ← Test pattern
    └── tasks/VoucherMintQuoteTaskTest.java     ← NEW: Tests
```

**REST Layer:**
```
cashu-mint-rest/
├── src/main/java/xyz/tcheeric/cashu/mint/rest/controller/
│   └── CashuController.java                    ← Add voucher quote endpoints
└── src/main/resources/application.properties   ← Optional config overrides
```

**Integration Tests:**
```
cashu-mint-rest-it/
└── src/test/java/xyz/tcheeric/cashu/mint/rest/it/
    └── VoucherMintQuoteIT.java                 ← NEW: End-to-end tests
```

---

## Implementation Phases

### Phase 1: Configuration Infrastructure Setup

**Goal:** Create the configuration system for voucher mint quote fee percentage

**Duration:** 1-2 days
**Dependencies:** None

#### Tasks

**1.1 Create VoucherFeeConfig utility class**

Location: `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/VoucherFeeConfig.java`

Pattern: Follow existing `FeeConfig.java:15-34`

Methods:
```java
public class VoucherFeeConfig {
    private static final String PROPERTY_KEY = "voucher.quote.fee-percent";
    private static final String ENV_KEY = "VOUCHER_QUOTE_FEE_PERCENT";
    private static final double DEFAULT_PERCENTAGE = 10.0;
    private static final double MAX_PERCENTAGE = 100.0;

    public static double getFeePercentage() { ... }
    public static void validatePercentage(double percentage) { ... }
    private static double loadFromProperties() { ... }
}
```

Loading precedence:
1. System property: `System.getProperty("voucher.quote.fee-percent")`
2. Environment variable: `System.getenv("VOUCHER_QUOTE_FEE_PERCENT")`
3. Property file: Read from `proto.properties`
4. Default: `10.0`

**1.2 Add configuration property**

File: `cashu-mint-protocol/src/main/resources/proto.properties`

Add:
```properties
# Voucher mint quote fee percentage (default: 10%)
# Percentage of voucher face value charged as minting fee
# Override with VOUCHER_QUOTE_FEE_PERCENT env var or -Dvoucher.quote.fee-percent system property
voucher.quote.fee-percent=10

# Optional: Maximum allowed percentage to prevent accidental markups
voucher.quote.fee-percent.max=100
```

**1.3 Implement validation logic**

Requirements:
- Reject negative percentages (throw `IllegalArgumentException`)
- Reject percentages > max (if max configured)
- Allow zero percentage (free minting)
- Log effective percentage at first access
- Provide clear error messages

Example validation:
```java
private static void validatePercentage(double percentage) {
    if (percentage < 0) {
        throw new IllegalArgumentException(
            "Voucher fee percentage cannot be negative: " + percentage);
    }
    double max = getMaxPercentage();
    if (percentage > max) {
        throw new IllegalArgumentException(
            String.format("Voucher fee percentage %.2f exceeds maximum %.2f",
                percentage, max));
    }
}
```

**1.4 Add startup logging**

Log effective configuration on first access:
```java
private static final Logger log = LoggerFactory.getLogger(VoucherFeeConfig.class);

static {
    double percentage = getFeePercentage();
    log.info("Voucher mint quote fee percentage: {}%", percentage);
}
```

#### Acceptance Criteria

- ✅ VoucherFeeConfig loads from all three sources correctly
- ✅ Precedence order is respected (system prop > env var > property file > default)
- ✅ Invalid values are rejected with clear error messages
- ✅ Effective percentage is logged at startup
- ✅ Zero percentage is allowed
- ✅ Configuration can be overridden without code changes

---

### Phase 2: Detection Mechanism & API Design

**Goal:** Determine how to identify voucher mint quote requests

**Duration:** 2-3 days
**Dependencies:** Phase 1

#### Critical Design Decision

**Question:** How do we distinguish voucher mint quotes from regular mint quotes?

**Recommended Approach: Option B - Dedicated Endpoint**

Create new endpoints:
- `POST /v1/mint/quote/voucher/{method}` - Create voucher mint quote
- `GET /v1/mint/quote/voucher/{method}/{quote_id}` - Get voucher quote status

**Rationale:**
- No changes to external cashu-lib dependency
- Clear separation of concerns
- Backward compatible
- Follows REST conventions
- Minimal code duplication (delegate to shared logic)

**Alternative Approaches Considered:**

*Option A: Add request parameter*
```json
{
  "amount": 1000,
  "isVoucher": true  // New field
}
```
- **Rejected:** Requires cashu-lib API change, affects external consumers

*Option C: Infer from payment method*
```
Use method "voucher" to indicate voucher mint quote
```
- **Rejected:** Couples payment method to quote type, ambiguous

#### Tasks

**2.1 Add new REST endpoints**

File: `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/controller/CashuController.java`

Add methods:
```java
@PostMapping("/mint/quote/voucher/{method}")
public PostMintQuoteResponse postVoucherMintQuote(
    @PathVariable String method,
    @RequestBody PostMintQuoteRequest request) {

    PaymentMethod paymentMethod = PaymentMethod.fromString(method);
    return NUT04.quoteVoucher(
        request.getAmount(),
        paymentMethod,
        mintId,
        gatewayResolver,
        quoteVault
    );
}

@GetMapping("/mint/quote/voucher/{method}/{quote_id}")
public PostMintQuoteResponse getVoucherMintQuoteStatus(
    @PathVariable String method,
    @PathVariable("quote_id") String quoteId) {

    PaymentMethod paymentMethod = PaymentMethod.fromString(method);
    return NUT04.voucherQuotePaymentStatus(quoteId, paymentMethod);
}
```

**2.2 Update NUT04.java**

File: `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NUT04.java`

Add static methods:
```java
public static PostMintQuoteResponse quoteVoucher(
    BigInteger amount,
    PaymentMethod method,
    UUID mintId,
    GatewayResolver gatewayResolver,
    QuoteVault quoteVault) {

    VoucherMintQuoteTask task = new VoucherMintQuoteTask(
        amount, method, mintId, gatewayResolver, quoteVault);
    return task.execute();
}

public static PostMintQuoteResponse voucherQuotePaymentStatus(
    String quoteId,
    PaymentMethod method) {

    // Delegate to existing MintQuoteStatusTask
    // No special handling needed for status checks
    MintQuoteStatusTask task = new MintQuoteStatusTask(quoteId, method);
    return task.execute();
}
```

**2.3 Create VoucherMintQuoteTask**

File: `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/VoucherMintQuoteTask.java`

Structure:
```java
@Slf4j
@AllArgsConstructor
public class VoucherMintQuoteTask implements Task<PostMintQuoteResponse> {
    private final BigInteger faceValue;
    private final PaymentMethod method;
    private final UUID mintId;
    private final GatewayResolver gatewayResolver;
    private final QuoteVault quoteVault;

    @Override
    public PostMintQuoteResponse execute() {
        // Implementation in Phase 3
    }
}
```

**2.4 Add endpoint documentation**

Add to API documentation (if OpenAPI spec exists):
- Document new endpoints
- Explain fee behavior
- Provide examples

#### Acceptance Criteria

- ✅ New endpoints are accessible
- ✅ Voucher mint quotes can be created via `/mint/quote/voucher/{method}`
- ✅ Regular mint quotes via `/mint/quote/{method}` remain unaffected
- ✅ Quote status endpoint works for voucher quotes
- ✅ Endpoints return standard `PostMintQuoteResponse` format
- ✅ Detection mechanism is clear and documented

---

### Phase 3: Core Fee Calculation Implementation

**Goal:** Implement the percentage-based fee calculation and gateway integration

**Duration:** 3-4 days
**Dependencies:** Phases 1-2

#### Tasks

**3.1 Create fee calculation utility**

File: `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/VoucherFeeCalculator.java`

Implementation:
```java
@Slf4j
public class VoucherFeeCalculator {

    /**
     * Calculate voucher minting fee using percentage-based pricing.
     *
     * @param voucherAmount The face value of the voucher in satoshis
     * @param feePercentage The fee percentage (e.g., 10.0 for 10%)
     * @return The fee amount in satoshis (floored to avoid overcharging)
     * @throws IllegalArgumentException if inputs are invalid
     * @throws ArithmeticException if calculation would overflow
     */
    public static long calculateFee(long voucherAmount, double feePercentage) {
        // Validate inputs
        if (voucherAmount < 0) {
            throw new IllegalArgumentException(
                "Voucher amount cannot be negative: " + voucherAmount);
        }
        if (feePercentage < 0) {
            throw new IllegalArgumentException(
                "Fee percentage cannot be negative: " + feePercentage);
        }

        // Handle zero cases (avoid unnecessary computation)
        if (voucherAmount == 0 || feePercentage == 0.0) {
            return 0L;
        }

        // Check for potential overflow
        // If amount * percentage / 100 would exceed Long.MAX_VALUE
        double maxSafeAmount = Long.MAX_VALUE / feePercentage * 100.0;
        if (voucherAmount > maxSafeAmount) {
            throw new ArithmeticException(
                String.format("Voucher amount %d with fee percentage %.2f%% would cause overflow",
                    voucherAmount, feePercentage));
        }

        // Calculate fee using floor to avoid overcharging
        double feeDouble = voucherAmount * feePercentage / 100.0;
        long fee = (long) Math.floor(feeDouble);

        log.debug("Calculated voucher fee: amount={}, percentage={}%, fee={}",
            voucherAmount, feePercentage, fee);

        return fee;
    }
}
```

Key features:
- Input validation (reject negatives)
- Overflow protection
- Floor rounding (per spec)
- Zero handling
- Debug logging

**3.2 Implement VoucherMintQuoteTask.execute()**

File: `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/VoucherMintQuoteTask.java`

Implementation:
```java
@Override
public PostMintQuoteResponse execute() {
    log.info("Creating voucher mint quote: faceValue={}, method={}", faceValue, method);

    // 1. Load fee percentage from configuration
    double feePercentage = VoucherFeeConfig.getFeePercentage();

    // 2. Calculate fee-based price
    long voucherPrice = VoucherFeeCalculator.calculateFee(
        faceValue.longValue(),
        feePercentage
    );

    log.info("Voucher mint quote: faceValue={}, feePercent={}%, chargedPrice={}",
        faceValue, feePercentage, voucherPrice);

    // 3. Create gateway and call createMintQuote with fee price
    Gateway gateway = gatewayResolver.resolve(method, unit);
    String quoteId = gateway.createMintQuote(BigInteger.valueOf(voucherPrice), null);

    // 4. Store face value for later minting (see section 3.3)
    VoucherQuoteRegistry.storeFaceValue(quoteId, faceValue.longValue());

    // 5. Retrieve request and expiry from gateway
    String request = gateway.getRequest(quoteId);
    Integer expiry = gateway.getExpiry(quoteId);

    // 6. Return standard response
    return PostMintQuoteResponse.builder()
        .quoteId(quoteId)
        .request(request)
        .expiry(expiry)
        .paid(false)
        .build();
}
```

**3.3 Create VoucherQuoteRegistry for face value storage**

File: `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/VoucherQuoteRegistry.java`

Purpose: Store mapping of quote ID to original face value for minting phase.

Implementation:
```java
@Slf4j
public class VoucherQuoteRegistry {
    private static final Map<String, Long> faceValues = new ConcurrentHashMap<>();

    /**
     * Store the face value for a voucher quote.
     */
    public static void storeFaceValue(String quoteId, long faceValue) {
        faceValues.put(quoteId, faceValue);
        log.debug("Stored face value for quote {}: {}", quoteId, faceValue);
    }

    /**
     * Retrieve the face value for a voucher quote.
     * Returns null if not found (regular mint quote).
     */
    public static Long getFaceValue(String quoteId) {
        return faceValues.get(quoteId);
    }

    /**
     * Remove face value after minting completes.
     */
    public static void removeFaceValue(String quoteId) {
        Long removed = faceValues.remove(quoteId);
        if (removed != null) {
            log.debug("Removed face value for quote {}: {}", quoteId, removed);
        }
    }

    /**
     * Check if quote is a voucher quote.
     */
    public static boolean isVoucherQuote(String quoteId) {
        return faceValues.containsKey(quoteId);
    }
}
```

**Note:** This is an in-memory solution. For production with multiple instances, consider:
- Redis-backed registry
- Database storage in quote table
- Gateway metadata (if supported)

**3.4 Update minting flow to use face value**

File: `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTask.java`

Modify to check for voucher quotes and use face value:

```java
@Override
public PostMintResponse execute() {
    // ... existing code ...

    // Check if this is a voucher quote
    Long voucherFaceValue = VoucherQuoteRegistry.getFaceValue(quoteId);

    if (voucherFaceValue != null) {
        log.info("Minting voucher quote {}: faceValue={}", quoteId, voucherFaceValue);

        // Validate that blinded messages total the face value
        BigInteger totalBlindedAmount = calculateTotalAmount(blindedMessages);
        if (!totalBlindedAmount.equals(BigInteger.valueOf(voucherFaceValue))) {
            throw new IllegalArgumentException(
                String.format("Blinded messages total %s does not match voucher face value %d",
                    totalBlindedAmount, voucherFaceValue));
        }

        // Mint using face value
        // ... proceed with minting ...

        // Clean up registry
        VoucherQuoteRegistry.removeFaceValue(quoteId);
    } else {
        // Regular mint quote - use amount from quote
        // ... existing logic ...
    }
}
```

**Note:** Exact integration point depends on current MintTask implementation. May need to verify in `MintTokensTask.java` instead.

**3.5 Add logging and metrics**

Add structured logging:
```java
log.info("Voucher mint quote created: quoteId={}, faceValue={}, feePercent={}%, chargedPrice={}, method={}",
    quoteId, faceValue, feePercentage, voucherPrice, method);
```

Optional metrics (if metrics framework available):
```java
// Counter
meterRegistry.counter("voucher.mint.quotes.created",
    "method", method.toString()).increment();

// Gauge for fee amount
meterRegistry.gauge("voucher.mint.quote.fee.sats", voucherPrice);

// Histogram for fee percentage
meterRegistry.summary("voucher.mint.quote.fee.percent").record(feePercentage);
```

#### Acceptance Criteria

- ✅ Fee calculation matches formula: `floor(amount * percentage / 100)`
- ✅ Overflow protection prevents crashes on large amounts
- ✅ Gateway receives correct fee-based price, not face value
- ✅ Original face value is preserved and retrievable
- ✅ Minting produces tokens totaling face value
- ✅ Regular (non-voucher) mints remain unaffected
- ✅ Logging provides complete audit trail
- ✅ Edge cases handled: zero amount, zero percentage, small amounts

---

### Phase 4: Unit Testing

**Goal:** Comprehensive unit test coverage for all new components

**Duration:** 2-3 days
**Dependencies:** Phases 1-3

#### Tasks

**4.1 Test VoucherFeeConfig**

File: `cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/util/VoucherFeeConfigTest.java`

Test cases:
```java
@Test
void testDefaultValue() {
    // Verify default of 10% when no config provided
}

@Test
void testEnvironmentVariableOverride() {
    // Mock System.getenv to return custom value
    // Verify env var takes precedence over property file
}

@Test
void testSystemPropertyOverride() {
    // Set system property
    // Verify system property takes precedence over env var
}

@Test
void testPrecedenceOrder() {
    // Set all three sources
    // Verify: system prop > env var > property file > default
}

@Test
void testInvalidNegativePercentage() {
    // Set negative value
    // Expect IllegalArgumentException
}

@Test
void testInvalidPercentageExceedsMax() {
    // Set percentage > 100 (if capped)
    // Expect IllegalArgumentException
}

@Test
void testZeroPercentageAllowed() {
    // Set 0%
    // Verify accepted
}

@Test
void testPropertyFileLoading() {
    // Verify loads from proto.properties correctly
}
```

**4.2 Test VoucherFeeCalculator**

File: `cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/util/VoucherFeeCalculatorTest.java`

Test cases:
```java
@Test
void testStandardCalculation() {
    // 1000 sats @ 10% = 100 sats
    long fee = VoucherFeeCalculator.calculateFee(1000, 10.0);
    assertEquals(100, fee);
}

@Test
void testLowPercentage() {
    // 1000 sats @ 1% = 10 sats
    long fee = VoucherFeeCalculator.calculateFee(1000, 1.0);
    assertEquals(10, fee);
}

@Test
void testZeroPercentage() {
    // Any amount @ 0% = 0 sats
    long fee = VoucherFeeCalculator.calculateFee(1000, 0.0);
    assertEquals(0, fee);
}

@Test
void testZeroAmount() {
    // 0 sats @ any % = 0 sats
    long fee = VoucherFeeCalculator.calculateFee(0, 10.0);
    assertEquals(0, fee);
}

@Test
void testFloorRounding() {
    // 1005 sats @ 1% = floor(10.05) = 10 sats
    long fee = VoucherFeeCalculator.calculateFee(1005, 1.0);
    assertEquals(10, fee);

    // 1 sat @ 50% = floor(0.5) = 0 sats
    fee = VoucherFeeCalculator.calculateFee(1, 50.0);
    assertEquals(0, fee);

    // 99 sats @ 1% = floor(0.99) = 0 sats
    fee = VoucherFeeCalculator.calculateFee(99, 1.0);
    assertEquals(0, fee);
}

@Test
void test100PercentFee() {
    // 1000 sats @ 100% = 1000 sats
    long fee = VoucherFeeCalculator.calculateFee(1000, 100.0);
    assertEquals(1000, fee);
}

@Test
void testLargeAmount() {
    // Test near max safe value
    long largeAmount = 1_000_000_000L; // 1 billion sats
    long fee = VoucherFeeCalculator.calculateFee(largeAmount, 10.0);
    assertEquals(100_000_000L, fee);
}

@Test
void testOverflowProtection() {
    // Amount that would cause overflow
    long unsafeAmount = Long.MAX_VALUE;
    assertThrows(ArithmeticException.class, () -> {
        VoucherFeeCalculator.calculateFee(unsafeAmount, 10.0);
    });
}

@Test
void testNegativeAmountRejected() {
    assertThrows(IllegalArgumentException.class, () -> {
        VoucherFeeCalculator.calculateFee(-1000, 10.0);
    });
}

@Test
void testNegativePercentageRejected() {
    assertThrows(IllegalArgumentException.class, () -> {
        VoucherFeeCalculator.calculateFee(1000, -10.0);
    });
}

@Test
void testFractionalPercentage() {
    // 10000 sats @ 0.5% = 50 sats
    long fee = VoucherFeeCalculator.calculateFee(10000, 0.5);
    assertEquals(50, fee);
}

@Test
void testVerySmallAmount() {
    // 5 sats @ 10% = floor(0.5) = 0 sats
    long fee = VoucherFeeCalculator.calculateFee(5, 10.0);
    assertEquals(0, fee);

    // 10 sats @ 10% = 1 sat
    fee = VoucherFeeCalculator.calculateFee(10, 10.0);
    assertEquals(1, fee);
}
```

**4.3 Test VoucherQuoteRegistry**

File: `cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/util/VoucherQuoteRegistryTest.java`

Test cases:
```java
@Test
void testStoreFaceValue() {
    String quoteId = "test-quote-1";
    VoucherQuoteRegistry.storeFaceValue(quoteId, 1000L);
    assertEquals(1000L, VoucherQuoteRegistry.getFaceValue(quoteId));
}

@Test
void testGetNonExistentQuote() {
    assertNull(VoucherQuoteRegistry.getFaceValue("non-existent"));
}

@Test
void testIsVoucherQuote() {
    String quoteId = "test-quote-2";
    assertFalse(VoucherQuoteRegistry.isVoucherQuote(quoteId));

    VoucherQuoteRegistry.storeFaceValue(quoteId, 1000L);
    assertTrue(VoucherQuoteRegistry.isVoucherQuote(quoteId));
}

@Test
void testRemoveFaceValue() {
    String quoteId = "test-quote-3";
    VoucherQuoteRegistry.storeFaceValue(quoteId, 1000L);
    VoucherQuoteRegistry.removeFaceValue(quoteId);
    assertNull(VoucherQuoteRegistry.getFaceValue(quoteId));
}

@Test
void testConcurrentAccess() {
    // Test thread safety with multiple concurrent operations
}
```

**4.4 Test VoucherMintQuoteTask**

File: `cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/tasks/VoucherMintQuoteTaskTest.java`

Pattern: Follow existing `MintQuoteTaskTest.java:21-60`

Test cases:
```java
@ExtendWith(MockitoExtension.class)
class VoucherMintQuoteTaskTest {
    @Mock private Gateway gateway;
    @Mock private GatewayResolver gatewayResolver;
    @Mock private QuoteVault quoteVault;

    private UUID mintId = UUID.randomUUID();

    @Test
    void testExecuteWithDefaultPercentage() {
        // Given: 1000 sat voucher, 10% default fee
        BigInteger faceValue = BigInteger.valueOf(1000);
        String expectedQuoteId = "quote-123";
        String expectedRequest = "lnbc1000n...";
        Integer expectedExpiry = 3600;

        when(gatewayResolver.resolve(any(), any())).thenReturn(gateway);
        when(gateway.createMintQuote(eq(BigInteger.valueOf(100)), isNull()))
            .thenReturn(expectedQuoteId);
        when(gateway.getRequest(expectedQuoteId)).thenReturn(expectedRequest);
        when(gateway.getExpiry(expectedQuoteId)).thenReturn(expectedExpiry);

        // When: Execute task
        VoucherMintQuoteTask task = new VoucherMintQuoteTask(
            faceValue, PaymentMethod.BOLT11, mintId, gatewayResolver, quoteVault);
        PostMintQuoteResponse response = task.execute();

        // Then: Gateway receives 100 sats, not 1000
        verify(gateway).createMintQuote(eq(BigInteger.valueOf(100)), isNull());

        // And: Response is correct
        assertEquals(expectedQuoteId, response.getQuoteId());
        assertEquals(expectedRequest, response.getRequest());
        assertEquals(expectedExpiry, response.getExpiry());
        assertFalse(response.isPaid());

        // And: Face value is stored
        assertEquals(1000L, VoucherQuoteRegistry.getFaceValue(expectedQuoteId));
    }

    @Test
    void testExecuteWith1PercentFee() {
        // Given: 1000 sat voucher, 1% fee (configured)
        // Mock config to return 1%
        // When: Execute
        // Then: Gateway receives 10 sats
    }

    @Test
    void testExecuteWithZeroPercentFee() {
        // Given: 1000 sat voucher, 0% fee
        // When: Execute
        // Then: Gateway receives 0 sats (free minting)
    }

    @Test
    void testExecuteWithSmallAmountResultingInZeroFee() {
        // Given: 5 sat voucher, 10% fee
        // When: Execute
        // Then: Gateway receives 0 sats (floor of 0.5)
    }

    @Test
    void testGatewayErrorPropagates() {
        // Given: Gateway throws exception
        when(gatewayResolver.resolve(any(), any())).thenReturn(gateway);
        when(gateway.createMintQuote(any(), any()))
            .thenThrow(new RuntimeException("Gateway error"));

        // When/Then: Exception propagates
        assertThrows(RuntimeException.class, () -> {
            new VoucherMintQuoteTask(
                BigInteger.valueOf(1000),
                PaymentMethod.BOLT11,
                mintId,
                gatewayResolver,
                quoteVault
            ).execute();
        });
    }
}
```

**4.5 Test NUT04 voucher quote methods**

File: Update existing `cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/nut/NUT04Test.java`

Add test cases:
```java
@Test
void testQuoteVoucher() {
    // Test NUT04.quoteVoucher() creates voucher quote correctly
}

@Test
void testVoucherQuotePaymentStatus() {
    // Test status check works for voucher quotes
}
```

**4.6 Test regular mints unaffected**

Ensure existing tests still pass:
```bash
mvn test -Dtest=MintQuoteTaskTest
mvn test -Dtest=NUT04Test
```

#### Acceptance Criteria

- ✅ All new classes have >80% line coverage
- ✅ Edge cases explicitly tested (zero, overflow, rounding)
- ✅ Mock patterns follow existing test conventions (`@ExtendWith(MockitoExtension.class)`)
- ✅ All tests pass in `mvn clean verify`
- ✅ Existing tests remain unaffected (regression protection)
- ✅ Test methods have descriptive comments

---

### Phase 5: Integration Testing

**Goal:** Validate end-to-end flows with real gateway and vault interactions

**Duration:** 2-3 days
**Dependencies:** Phases 1-4

#### Tasks

**5.1 Create VoucherMintQuoteIT integration test**

File: `cashu-mint-rest-it/src/test/java/xyz/tcheeric/cashu/mint/rest/it/VoucherMintQuoteIT.java`

Structure:
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VoucherMintQuoteIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private Gateway gateway;  // Or use WireMock for gateway

    @Test
    void testCompleteVoucherMintFlow() {
        // Full flow test (see 5.2)
    }

    @Test
    void testVoucherQuoteWith1PercentFee() {
        // Test with custom fee percentage
    }

    @Test
    void testVoucherQuoteEdgeCases() {
        // Small amounts, zero fee, etc.
    }

    @Test
    void testRegularMintQuoteUnaffected() {
        // Ensure /mint/quote/{method} still works normally
    }
}
```

**5.2 Test complete voucher mint flow**

Test steps:
```java
@Test
void testCompleteVoucherMintFlow() {
    // 1. Create voucher mint quote
    PostMintQuoteRequest request = new PostMintQuoteRequest();
    request.setAmount(BigInteger.valueOf(1000));

    ResponseEntity<PostMintQuoteResponse> quoteResponse = restTemplate.postForEntity(
        "/v1/mint/quote/voucher/bolt11",
        request,
        PostMintQuoteResponse.class
    );

    assertEquals(HttpStatus.OK, quoteResponse.getStatusCode());
    PostMintQuoteResponse quote = quoteResponse.getBody();
    assertNotNull(quote.getQuoteId());
    assertFalse(quote.isPaid());

    // 2. Verify invoice is for 100 sats (10% of 1000)
    String invoiceRequest = quote.getRequest();
    // Parse BOLT11 invoice and verify amount is 100 sats
    // (Use bolt11 decoder library or mock verification)

    // 3. Mark invoice as paid in mock gateway
    when(gateway.isPaid(quote.getQuoteId())).thenReturn(true);

    // 4. Check quote status
    ResponseEntity<PostMintQuoteResponse> statusResponse = restTemplate.getForEntity(
        "/v1/mint/quote/voucher/bolt11/" + quote.getQuoteId(),
        PostMintQuoteResponse.class
    );

    assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
    assertTrue(statusResponse.getBody().isPaid());

    // 5. Mint tokens
    PostMintRequest mintRequest = new PostMintRequest();
    mintRequest.setQuote(quote.getQuoteId());
    mintRequest.setOutputs(createBlindedMessages(1000));  // 1000 sats total

    ResponseEntity<PostMintResponse> mintResponse = restTemplate.postForEntity(
        "/v1/mint/bolt11",
        mintRequest,
        PostMintResponse.class
    );

    assertEquals(HttpStatus.OK, mintResponse.getStatusCode());

    // 6. Verify minted tokens total 1000 sats (face value), not 100 sats
    List<BlindSignature> signatures = mintResponse.getBody().getSignatures();
    BigInteger totalMinted = signatures.stream()
        .map(sig -> sig.getAmount())
        .reduce(BigInteger.ZERO, BigInteger::add);

    assertEquals(BigInteger.valueOf(1000), totalMinted);
}

private List<BlindedMessage> createBlindedMessages(long totalAmount) {
    // Helper to create blinded messages totaling the given amount
    // Use standard denominations: 1, 2, 4, 8, 16, 32, 64, etc.
}
```

**5.3 Test different fee percentages**

```java
@Test
@TestPropertySource(properties = {"voucher.quote.fee-percent=1"})
void testVoucherQuoteWith1PercentFee() {
    // Create quote for 1000 sats
    // Verify invoice is for 10 sats (1% of 1000)
}

@Test
@TestPropertySource(properties = {"voucher.quote.fee-percent=0"})
void testVoucherQuoteWithZeroFee() {
    // Create quote for 1000 sats
    // Verify invoice is for 0 sats (free minting)
}

@Test
@TestPropertySource(properties = {"voucher.quote.fee-percent=50"})
void testVoucherQuoteWith50PercentFee() {
    // Create quote for 1000 sats
    // Verify invoice is for 500 sats
}

@Test
@TestPropertySource(properties = {"voucher.quote.fee-percent=100"})
void testVoucherQuoteWith100PercentFee() {
    // Create quote for 1000 sats
    // Verify invoice is for 1000 sats
}
```

**5.4 Test edge cases**

```java
@Test
void testVerySmallVoucherAmount() {
    // 10 sats @ 10% = 1 sat fee
    PostMintQuoteRequest request = new PostMintQuoteRequest();
    request.setAmount(BigInteger.valueOf(10));

    ResponseEntity<PostMintQuoteResponse> response = restTemplate.postForEntity(
        "/v1/mint/quote/voucher/bolt11",
        request,
        PostMintQuoteResponse.class
    );

    assertEquals(HttpStatus.OK, response.getStatusCode());
    // Verify invoice is for 1 sat
}

@Test
void testAmountResultingInZeroFee() {
    // 5 sats @ 10% = floor(0.5) = 0 sats
    // Should this be allowed or rejected?
}

@Test
void testVeryLargeVoucherAmount() {
    // Test with large amount (e.g., 100M sats)
    // Ensure no overflow errors
}
```

**5.5 Test non-voucher mints unaffected**

```java
@Test
void testRegularMintQuoteUnaffected() {
    // Create regular mint quote via /mint/quote/bolt11
    PostMintQuoteRequest request = new PostMintQuoteRequest();
    request.setAmount(BigInteger.valueOf(1000));

    ResponseEntity<PostMintQuoteResponse> response = restTemplate.postForEntity(
        "/v1/mint/quote/bolt11",
        request,
        PostMintQuoteResponse.class
    );

    assertEquals(HttpStatus.OK, response.getStatusCode());

    // Verify invoice is for full 1000 sats, not 100
    // (No percentage fee applied to regular mints)
}
```

**5.6 Test configuration override mechanisms**

```java
@Test
void testEnvironmentVariableOverride() {
    // Set VOUCHER_QUOTE_FEE_PERCENT env var
    // Restart application context
    // Verify new percentage is used
}

@Test
void testSystemPropertyOverride() {
    // Launch with -Dvoucher.quote.fee-percent=15
    // Verify 15% fee is used
}
```

**5.7 Run integration tests**

```bash
# Run all integration tests
mvn clean verify -Pintegration-tests

# Run specific IT
mvn verify -Pintegration-tests -Dit.test=VoucherMintQuoteIT
```

#### Acceptance Criteria

- ✅ Full voucher mint flow works end-to-end
- ✅ Fee calculation matches spec at REST API level
- ✅ Minting produces correct token amounts (face value, not fee)
- ✅ Regular mints remain unaffected
- ✅ Configuration overrides work in integration tests
- ✅ Edge cases handled gracefully
- ✅ All integration tests pass with Testcontainers/WireMock
- ✅ Performance is acceptable (no significant slowdown)

---

### Phase 6: Documentation & Validation

**Goal:** Document the feature and ensure production readiness

**Duration:** 1-2 days
**Dependencies:** Phases 1-5

#### Tasks

**6.1 Update feature specification**

File: `docs/explanations/voucher-mint-quote-percentage.md`

Add section:
```markdown
## Implementation Details

**Status:** ✅ Implemented in version 0.3.4

### File Locations

**Protocol Layer:**
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NUT04.java` - Added `quoteVoucher()` method
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/VoucherMintQuoteTask.java` - Voucher quote creation task
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/VoucherFeeConfig.java` - Configuration loader
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/VoucherFeeCalculator.java` - Fee calculation utility

**REST Layer:**
- `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/controller/CashuController.java` - Voucher quote endpoints

### Detection Mechanism

Voucher mint quotes are created via dedicated endpoints:
- `POST /v1/mint/quote/voucher/{method}` - Create voucher quote
- `GET /v1/mint/quote/voucher/{method}/{quote_id}` - Check status

Regular mint quotes (`POST /v1/mint/quote/{method}`) remain unaffected.

### Configuration

See [How to Configure Voucher Mint Fees](../how-to/configure-voucher-mint-fees.md) for detailed configuration instructions.

### Troubleshooting

**Issue:** Quote creation fails with "Voucher fee percentage invalid"
- **Cause:** Negative or excessive percentage configured
- **Solution:** Check `proto.properties`, env vars, and system properties

**Issue:** Minted tokens don't match voucher face value
- **Cause:** Blinded messages total incorrect amount
- **Solution:** Ensure blinded messages sum to original voucher amount, not fee price
```

**6.2 Create how-to guide**

File: `docs/how-to/configure-voucher-mint-fees.md`

Content:
```markdown
# How to Configure Voucher Mint Fees

This guide explains how to configure the percentage-based fee for voucher mint quotes.

## Overview

Voucher mint quotes charge a configurable percentage of the voucher face value, not the full amount. For example, with a 10% fee, a 1,000 sat voucher requires paying only 100 sats to mint it.

## Configuration Methods

### 1. Property File (Default)

Edit `cashu-mint-protocol/src/main/resources/proto.properties`:

```properties
# Set fee percentage (default: 10%)
voucher.quote.fee-percent=10
```

### 2. Environment Variable (Deployment)

Set environment variable for the mint process:

```bash
export VOUCHER_QUOTE_FEE_PERCENT=5
```

Useful for Docker deployments:

```yaml
# docker-compose.yml
services:
  cashu-mint:
    environment:
      - VOUCHER_QUOTE_FEE_PERCENT=5
```

### 3. System Property (Per-Process)

Pass as JVM argument:

```bash
java -Dvoucher.quote.fee-percent=15 -jar cashu-mint-rest.jar
```

Or in Maven:

```bash
mvn -Dvoucher.quote.fee-percent=15 spring-boot:run
```

## Precedence Order

When multiple sources are configured, this order applies:

1. **System property** (`-Dvoucher.quote.fee-percent`)
2. **Environment variable** (`VOUCHER_QUOTE_FEE_PERCENT`)
3. **Property file** (`proto.properties`)
4. **Default** (10%)

## Common Scenarios

### Scenario 1: 1% Fee (1,000 sats → 10 sats)

```bash
export VOUCHER_QUOTE_FEE_PERCENT=1
```

### Scenario 2: Free Minting (0% Fee)

```bash
export VOUCHER_QUOTE_FEE_PERCENT=0
```

Use case: Promotional periods, internal testing.

### Scenario 3: 50% Fee

```properties
voucher.quote.fee-percent=50
```

Use case: High-value vouchers, premium services.

### Scenario 4: Per-Deployment Configuration

**Staging:**
```yaml
# docker-compose.staging.yml
environment:
  - VOUCHER_QUOTE_FEE_PERCENT=1  # Low fee for testing
```

**Production:**
```yaml
# docker-compose.prod.yml
environment:
  - VOUCHER_QUOTE_FEE_PERCENT=10  # Standard fee
```

## Validation Rules

- **Minimum:** 0% (free minting allowed)
- **Maximum:** 100% (configurable via `voucher.quote.fee-percent.max`)
- **Negative values:** Rejected with error
- **Excessive values:** Rejected if > max (default max: 100%)

## Verification

Check effective configuration in logs on startup:

```
INFO  VoucherFeeConfig - Voucher mint quote fee percentage: 10.0%
```

## Monitoring

Track fee collection with logs:

```
INFO  VoucherMintQuoteTask - Voucher mint quote created: quoteId=abc123, faceValue=1000, feePercent=10%, chargedPrice=100, method=bolt11
```

## Troubleshooting

### Configuration Not Applied

1. Check logs for effective percentage on startup
2. Verify environment variables: `env | grep VOUCHER`
3. Check system properties: `jinfo <pid> | grep voucher`
4. Ensure no typos: `voucher.quote.fee-percent` (not `fee-percentage`)

### Invalid Configuration Error

```
IllegalArgumentException: Voucher fee percentage cannot be negative: -5
```

**Solution:** Set valid percentage (0-100)

### Fee Seems Wrong

Verify calculation manually:
```
fee = floor(amount * percentage / 100)

Example: 1,000 sats @ 10% = floor(1000 * 10 / 100) = floor(100.0) = 100 sats
Example: 1,005 sats @ 1% = floor(1005 * 1 / 100) = floor(10.05) = 10 sats
```

Note: Floor rounding prevents overcharging on fractional amounts.

## See Also

- [Voucher Mint Quote Percentage Feature Spec](../explanations/voucher-mint-quote-percentage.md)
- [Configure Gateways](configure-gateways.md)
```

**6.3 Update API documentation**

If OpenAPI/Swagger spec exists, add:

```yaml
/v1/mint/quote/voucher/{method}:
  post:
    summary: Create voucher mint quote
    description: |
      Creates a mint quote for a voucher with percentage-based fee.
      The invoice amount will be a percentage of the voucher face value
      (configured via voucher.quote.fee-percent, default 10%).

      Example: With 10% fee, a 1,000 sat voucher creates a 100 sat invoice.
    parameters:
      - name: method
        in: path
        required: true
        schema:
          type: string
          enum: [bolt11]
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/PostMintQuoteRequest'
          example:
            amount: 1000
    responses:
      200:
        description: Quote created successfully
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/PostMintQuoteResponse'
            example:
              quote: "abc123..."
              request: "lnbc100n..."  # 100 sats invoice for 1000 sat voucher
              paid: false
              expiry: 1640000000
```

**6.4 Update docs/README.md**

File: `docs/README.md`

Add entry:
```markdown
### How-To Guides

- [Configure Voucher Mint Fees](how-to/configure-voucher-mint-fees.md)

### Explanations

- [Voucher Mint Quote Percentage Fee Implementation Plan](explanations/voucher-mint-quote-percentage-implementation-plan.md)
```

**6.5 Update mint.yaml if needed**

File: `cashu-mint-protocol/src/main/resources/mint.yaml`

If voucher minting is a new capability:
```yaml
nuts:
  4:
    methods:
      - method: bolt11
        unit: sat
        min_amount: 1
        max_amount: 1000000
    # Add voucher-specific settings if needed
    voucher:
      enabled: true
      fee_percent: 10
```

**6.6 Add configuration reference**

Update main README or create configuration reference document:

```markdown
## Configuration Reference

### Voucher Settings

| Property | Env Variable | Default | Description |
|----------|--------------|---------|-------------|
| `voucher.quote.fee-percent` | `VOUCHER_QUOTE_FEE_PERCENT` | `10` | Percentage fee for voucher mint quotes (0-100) |
| `voucher.quote.fee-percent.max` | `VOUCHER_QUOTE_FEE_PERCENT_MAX` | `100` | Maximum allowed fee percentage |
```

**6.7 Create migration guide**

File: `docs/how-to/migrate-to-percentage-fees.md`

If changing existing behavior:
```markdown
# Migrating to Percentage-Based Voucher Fees

## Overview

Version 0.3.4 introduces percentage-based fees for voucher mint quotes.

## Changes

- **Before:** Vouchers charged full face value
- **After:** Vouchers charge configurable percentage (default 10%)

## Migration Steps

1. **Review fee configuration** - Default is 10%, adjust if needed
2. **Update client expectations** - Invoices will be for fee amount, not face value
3. **Test voucher flows** - Verify minting still works correctly
4. **Monitor logs** - Check fee calculations in production

## Rollback

To revert to 100% fees (original behavior):

```bash
export VOUCHER_QUOTE_FEE_PERCENT=100
```

## Backward Compatibility

- Regular mint quotes (`/v1/mint/quote/{method}`) unchanged
- Voucher quotes require new endpoint (`/v1/mint/quote/voucher/{method}`)
- Existing quotes created before upgrade will complete normally
```

**6.8 Code review checklist**

Before merging, verify:

- [ ] Follows Clean Code principles (chapters 2, 3, 4, 7, 9, 10, 17)
- [ ] Google Java Format applied (`mvn com.coveo:fmt-maven-plugin:format`)
- [ ] Lombok used for boilerplate (@Slf4j, @AllArgsConstructor, etc.)
- [ ] Unit tests pass (`mvn clean test`)
- [ ] Integration tests pass (`mvn clean verify -Pintegration-tests`)
- [ ] Code coverage >80% for new classes
- [ ] No security vulnerabilities (command injection, XSS, SQL injection)
- [ ] No over-engineering (YAGNI principle)
- [ ] Conventional commit messages
- [ ] Documentation complete and accurate

**6.9 Performance validation**

Test performance impact:

```bash
# Benchmark quote creation before/after
ab -n 1000 -c 10 http://localhost:7777/v1/mint/quote/voucher/bolt11

# Compare to regular mint quotes
ab -n 1000 -c 10 http://localhost:7777/v1/mint/quote/bolt11

# Ensure no significant regression (<5% slower)
```

**6.10 Security review**

Verify:
- [ ] No integer overflow exploits (large amounts handled safely)
- [ ] Configuration validation prevents malicious values
- [ ] Logging doesn't expose sensitive data
- [ ] No injection vulnerabilities in quote handling
- [ ] Fee calculation can't be bypassed

#### Acceptance Criteria

- ✅ Feature fully documented in Diátaxis structure
- ✅ Configuration clearly explained with examples
- ✅ API changes documented (OpenAPI spec updated)
- ✅ Migration guide provided (if applicable)
- ✅ Code passes all quality checks (`mvn clean verify`)
- ✅ Code review checklist complete
- ✅ Performance validated (no significant regression)
- ✅ Security reviewed
- ✅ Ready for production deployment

---

## Critical Decision Points

Before starting implementation, these decisions must be made:

### 1. Detection Mechanism ⚠️ HIGH PRIORITY

**Question:** How to distinguish voucher mint quotes from regular mint quotes?

**Options:**

| Option | API Impact | Implementation | Recommended |
|--------|-----------|----------------|-------------|
| A: Request parameter | Breaking change to cashu-lib | Add `isVoucher` field to request | ❌ No |
| B: Dedicated endpoint | New endpoints only | Create `/mint/quote/voucher/{method}` | ✅ **Yes** |
| C: Payment method | None | Check for method "voucher" | ❌ No (ambiguous) |

**Recommended:** Option B (dedicated endpoint)

**Rationale:**
- No external dependency changes
- Clear API semantics
- Backward compatible
- Easy to document and test

**Action Required:** Confirm with stakeholders before Phase 2.

### 2. Face Value Storage ⚠️ MEDIUM PRIORITY

**Question:** Where to store original face value for minting phase?

**Options:**

| Option | Pros | Cons | Scalability |
|--------|------|------|-------------|
| In-memory registry | Simple, fast | Lost on restart, not distributed | ❌ Single instance only |
| Gateway metadata | Centralized | Requires gateway support | ✅ If supported |
| Database (vault) | Persistent, distributed | Requires schema change | ✅ Production-ready |
| Redis cache | Fast, distributed | Requires Redis | ✅ Production-ready |

**Recommended:** Start with in-memory registry (Phase 3), migrate to database or Redis for production.

**Action Required:** Plan database migration or Redis integration for multi-instance deployments.

### 3. Default Fee Percentage ⚠️ LOW PRIORITY

**Question:** What should the default percentage be?

**Spec says:**
- Default: 10%
- Example scenario: 1,000 sats → 10 sats (1% fee)

**Clarification needed:** Are these contradictory?

**Options:**
- Default 10% (follows spec literally)
- Default 1% (matches example scenario)
- Make default configurable per deployment

**Recommended:** Default 10%, document example with 1% as override.

**Action Required:** Clarify business requirement with stakeholders.

### 4. Percentage Cap ⚠️ LOW PRIORITY

**Question:** Should percentages >100% be allowed?

**Options:**
- Hard cap at 100% (prevent accidental markups)
- Configurable cap (allow surcharges if needed)
- No cap (full flexibility)

**Recommended:** Configurable cap with default 100%.

**Action Required:** Confirm business rules for surcharges.

### 5. Zero Fee Handling ⚠️ LOW PRIORITY

**Question:** Should zero fees be allowed (free minting)?

**Use cases:**
- Promotional periods
- Internal testing
- Community events

**Recommended:** Allow zero fees (spec explicitly permits it).

**Action Required:** None, implement as specified.

---

## Risk Assessment

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| **cashu-lib API changes** | High | Low | Use Option B (dedicated endpoint) to avoid |
| **Gateway doesn't support metadata** | Medium | Medium | Implement internal mapping service |
| **Integer overflow on large amounts** | High | Low | Add overflow detection, use long/BigInteger |
| **Backward compatibility** | Medium | Low | Keep regular mints separate, test thoroughly |
| **Configuration not loaded** | Low | Medium | Add startup validation and logging |
| **Face value lost on restart** | Medium | Medium | Document in-memory registry limitation, plan migration |
| **Multi-instance deployments** | High | High | Require distributed storage for production |
| **Performance regression** | Low | Low | Benchmark and optimize calculation |
| **Security: fee bypass** | High | Low | Validate quote ownership on minting |
| **Security: overflow exploits** | High | Low | Implement overflow protection |

---

## Testing Strategy

### Unit Tests (Phase 4)

**Coverage target:** >80% line coverage for all new classes

**Focus areas:**
- Configuration loading and precedence
- Fee calculation correctness
- Edge cases (zero, overflow, rounding)
- Error handling
- Registry operations

**Tools:**
- JUnit 5
- Mockito for mocking
- AssertJ for assertions

### Integration Tests (Phase 5)

**Scope:** End-to-end voucher mint flows

**Test environments:**
- Spring Boot test context
- Testcontainers for PostgreSQL
- WireMock for gateway mocking

**Key flows:**
1. Create voucher quote → Pay fee → Mint tokens
2. Configuration overrides (env, system prop)
3. Edge cases with real gateway
4. Regular mints unaffected

### Regression Tests

**Ensure existing functionality unaffected:**
- Run full test suite: `mvn clean verify -Pintegration-tests`
- Verify existing NUT04 tests pass
- Check gateway integration tests
- Validate vault operations

### Performance Tests

**Benchmarks:**
- Quote creation latency (<100ms)
- Throughput (>100 quotes/sec)
- Memory usage (no leaks in registry)

**Tools:**
- Apache Bench (ab)
- JMeter (optional)
- Java Flight Recorder (profiling)

---

## Implementation Phases Summary

| Phase | Duration | Dependencies | Key Deliverables | Risk Level |
|-------|----------|--------------|------------------|------------|
| **Phase 1: Configuration** | 1-2 days | None | VoucherFeeConfig, property loading | Low |
| **Phase 2: Detection** | 2-3 days | Phase 1 | Dedicated endpoint, API design | Medium |
| **Phase 3: Calculation** | 3-4 days | Phases 1-2 | Fee calculator, task, registry | Medium |
| **Phase 4: Unit Tests** | 2-3 days | Phases 1-3 | >80% coverage, edge cases | Low |
| **Phase 5: Integration Tests** | 2-3 days | Phases 1-4 | End-to-end validation | Medium |
| **Phase 6: Documentation** | 1-2 days | Phases 1-5 | Docs, migration guide | Low |

**Total estimated effort:** 11-17 days

**Critical path:** Phases 1 → 2 → 3 (foundation)

**Parallel opportunities:**
- Phase 4 (unit tests) can start during Phase 3 for completed components
- Phase 6 (documentation) can be drafted during Phases 3-5

---

## Next Steps

1. **Review this plan** with technical lead and stakeholders
2. **Make critical decisions:**
   - Confirm detection mechanism (dedicated endpoint)
   - Plan face value storage for production
   - Clarify default percentage and cap
3. **Update feature spec** (`voucher-mint-quote-percentage.md`) with decisions
4. **Create implementation branch:**
   ```bash
   git checkout -b feature/voucher-quote-percentage-fee
   ```
5. **Begin Phase 1** implementation
6. **Track progress** using todo list and commit frequently

## Questions for Stakeholders

Before implementation, please clarify:

1. **Default fee percentage:** 10% (spec) or 1% (example)?
2. **Percentage cap:** Should values >100% be allowed?
3. **Zero fee:** Confirm free minting (0%) is acceptable for promotions
4. **Production deployment:** Single instance or multi-instance? (affects storage choice)
5. **Gateway metadata:** Does the gateway implementation support storing metadata on quotes?

---

## Success Criteria

Implementation is complete when:

- ✅ All 6 phases completed
- ✅ All tests pass (unit + integration)
- ✅ Code coverage >80% for new classes
- ✅ Documentation complete and accurate
- ✅ Code review approved
- ✅ Performance validated (no regression)
- ✅ Security reviewed
- ✅ Configuration works via all three methods (property, env, system prop)
- ✅ Regular mints verified unaffected
- ✅ Production deployment plan documented

---

## References

- [Voucher Mint Quote Percentage Feature Spec](voucher-mint-quote-percentage.md)
- [CLAUDE.md - Project Development Guide](../../CLAUDE.md)
- [Clean Code Principles](https://github.com/iluwatar/java-design-patterns)
- [NUT-04 Specification](https://github.com/cashubtc/nuts/blob/main/04.md)
