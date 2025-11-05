# Representing vouchers as structured secrets

Designing Cashu vouchers as structured secrets lets you enrich the blinded preimage with the voucher metadata that wallets and mints need while still speaking the standard Cashu protocol. The REST layer, protocol tasks, and vault services are already generic in the secret type, so you can swap in a richer implementation without changing endpoint contracts.

## Table of Contents

- [Understand the existing secret contract](#understand-the-existing-secret-contract)
  - [The Secret interface](#the-secret-interface)
  - [Existing implementations](#existing-implementations)
  - [Polymorphic deserialization](#polymorphic-deserialization)
  - [Hash-to-curve integration](#hash-to-curve-integration)
  - [Key expectations for a voucher secret implementation](#key-expectations-for-a-voucher-secret-implementation)
- [Model the voucher secret](#model-the-voucher-secret)
  - [Field organization](#field-organization)
  - [Builder pattern example](#builder-pattern-example)
- [Wire the secret through the mint workflow](#wire-the-secret-through-the-mint-workflow)
- [Define the voucher JSON schema](#define-the-voucher-json-schema)
  - [Example VoucherSecret JSON](#example-vouchersecret-json)
  - [Field semantics](#field-semantics)
  - [Hash-to-curve integration](#hash-to-curve-integration-1)
  - [Deterministic serialization](#deterministic-serialization)
- [Implement validation rules](#implement-validation-rules)
  - [Relationship to NUT-11 and NUT-14 validation](#relationship-to-nut-11-and-nut-14-validation)
  - [The voucher issuer allowlist](#the-voucher-issuer-allowlist)
  - [Who maintains the allowlist?](#who-maintains-the-allowlist)
  - [Recommended approach: Hybrid model](#recommended-approach-hybrid-model)
  - [Allowlist publication and discovery](#allowlist-publication-and-discovery)
  - [Pre-mint validation](#pre-mint-validation)
  - [Error codes](#error-codes)
- [Handle schema versioning](#handle-schema-versioning)
  - [Version evolution strategy](#version-evolution-strategy)
  - [Mint compatibility matrix](#mint-compatibility-matrix)
  - [Wallet version handling](#wallet-version-handling)
- [Wire through the REST and protocol layers](#wire-through-the-rest-and-protocol-layers)
  - [Extend WellKnownSecretDeserializer](#extend-wellknownsecretdeserializer)
  - [Automatic polymorphic handling](#automatic-polymorphic-handling)
  - [Request flow example](#request-flow-example)
  - [Database persistence](#database-persistence)
- [Implementation impact across repositories](#implementation-impact-across-repositories)
  - [cashu-lib changes](#cashu-lib-changes)
  - [cashu-mint changes](#cashu-mint-changes)
  - [cashu-client changes](#cashu-client-changes)
  - [Optional cashu-voucher module](#optional-cashu-voucher-module)
- [Security considerations](#security-considerations)
  - [Issuer signature verification](#issuer-signature-verification)
  - [Replay prevention](#replay-prevention)
  - [Expiry and time synchronization](#expiry-and-time-synchronization)
  - [Privacy implications](#privacy-implications)
  - [Double-spending and redemption limits](#double-spending-and-redemption-limits)
- [Versioning and interoperability](#versioning-and-interoperability)
  - [Schema publication](#schema-publication)
  - [Recommended Approach: NIP-87 Pattern](#recommended-approach-nip-87-pattern)
  - [Issuer Announcement Structure (kind:38174)](#issuer-announcement-structure-kind38174)
  - [Issuer Recommendation Structure (kind:38001)](#issuer-recommendation-structure-kind38001)
  - [Discovery Flow](#discovery-flow)
  - [Issuer Key Rotation](#issuer-key-rotation)
  - [Alternative: NIP-51 Curated Lists](#alternative-nip-51-curated-lists)
  - [Combined Strategy](#combined-strategy)
  - [Wallet integration guide](#wallet-integration-guide)
  - [Cross-mint compatibility](#cross-mint-compatibility)
  - [Testing and compliance](#testing-and-compliance)

## Understand the existing secret contract

Every entry point that handles blinded outputs or proofs works with the `Secret` interface abstraction. For example, the public controller declares `CashuController<T extends Secret>`, forwards request payloads to NUT helpers, and never inspects the fields of `T` directly. `MintTask`, `MintTokensTask`, and the melt/swap tasks repeat the same pattern: they receive `Proof<T>` or `BlindedMessage<T>` objects, call the elliptic-curve signing routines, and return blind signatures that the client later unblinds.

### The Secret interface

The `Secret` interface defines a minimal contract with just three core methods:

```java
public interface Secret {
    byte[] getData();                    // Get underlying byte data
    void setData(@NonNull byte[] data);  // Update byte data
    byte[] toBytes();                    // Convert to canonical bytes for hash-to-curve
}
```

### Existing implementations

The cashu-lib provides two primary Secret implementations:

**1. RandomStringSecret (RSSecret)** - Simple random string secrets:
- Extends `BaseKey` and implements `Secret`
- Stores a hex-encoded random string (typically 32 bytes per NUT-00)
- `toString()` returns the hex-encoded string
- `toBytes()` returns the raw bytes
- Used for basic Cashu ecash without spending conditions
- Immutable (setData() is a no-op)

**2. P2PKSecret** - Pay-to-Public-Key secrets (NUT-11):
- Extends `WellKnownSecret` and implements `Secret`
- Structured format with `kind`, `nonce`, `data`, and extensible `tags`
- `toString()` returns full JSON representation
- `toBytes()` returns JSON bytes for hash-to-curve
- Supports multisig, locktime, refund keys, and signature flags
- Mutable tag system for spending conditions

**The WellKnownSecret base class:**

`P2PKSecret` inherits from the abstract `WellKnownSecret` class, which provides:
- A `Kind` enum (currently `P2PK`, with `HTLC` planned)
- A `nonce` field for uniqueness
- A `data` field for the primary cryptographic material (e.g., public key)
- An extensible `tags` system for additional constraints
- JSON serialization/deserialization via Jackson custom handlers

**Example P2PKSecret JSON representation:**
```json
{
  "kind": "P2PK",
  "nonce": "f3a2b1c4d5e6f7a8",
  "data": "02a1b2c3d4e5f6...",
  "tags": [
    {"key": "sigflag", "values": ["SIG_INPUTS"]},
    {"key": "n_sigs", "values": [2]},
    {"key": "pubkeys", "values": ["02abc...", "03def..."]},
    {"key": "locktime", "values": [1735689600]},
    {"key": "refund", "values": ["02xyz..."]}
  ]
}
```

### Polymorphic deserialization

The cashu-lib uses Jackson's `@JsonDeserialize` with a custom `SecretDeserializer` on the `Proof.secret` field:

```java
public class SecretDeserializer extends JsonDeserializer<Secret> {
    @Override
    public Secret deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();

        if (node.isTextual()) {
            // String value -> RandomStringSecret
            return RandomStringSecret.fromString(node.textValue());
        }

        if (node.isObject()) {
            // Object with "kind" field -> WellKnownSecret (dispatches to P2PKSecret, etc.)
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            return mapper.treeToValue(node, WellKnownSecret.class);
        }

        throw new RuntimeException("Invalid Secret format");
    }
}
```

This means:
- A string in the `secret` field deserializes to `RandomStringSecret`
- An object with a `kind` field deserializes to the appropriate `WellKnownSecret` subclass

### Hash-to-curve integration

All Secret implementations must provide `toBytes()` which returns the canonical byte representation used for hash-to-curve:

```java
// SecretUtil.toY() - converts any Secret to curve point
public static <T extends Secret> String toY(@NonNull T secret) {
    return PublicKey.fromPoint(
        BDHKEUtils.hashToCurve(secret.toBytes()),  // BDHKE hash-to-curve
        true).toString();
}
```

For `RandomStringSecret`: `toBytes()` returns the raw hex-decoded bytes.
For `P2PKSecret` and other `WellKnownSecret` subclasses: `toBytes()` returns the JSON string bytes.

This indirection means that as long as your voucher secret extends `WellKnownSecret` and implements the `toBytes()` contract correctly, the rest of the mint remains agnostic to the voucher structure.

### Key expectations for a voucher secret implementation

Following the existing patterns, a voucher secret should:

- **Extend WellKnownSecret.** This provides the structured format, nonce uniqueness, tag system, and JSON serialization infrastructure.
- **Define a new Kind.** Add `VOUCHER` to the `WellKnownSecret.Kind` enum (alongside `P2PK` and `HTLC`).
- **Use the tag system.** Store voucher metadata (issuer, expiry, face value, etc.) as tags, following the P2PK pattern.
- **Implement deterministic toBytes().** The JSON representation must be canonical so hash-to-curve produces consistent results.
- **Register deserializer.** Extend `WellKnownSecretDeserializer` to handle the `VOUCHER` kind.
- **Create a SpendingCondition.** Implement `SpendingCondition<VoucherSecret>` for validation, like `P2PKSpendingCondition` does for P2PK.

## Model the voucher secret

Following the `P2PKSecret` pattern, create a `VoucherSecret` class that extends `WellKnownSecret`:

```java
public class VoucherSecret extends WellKnownSecret {

    // Voucher-specific tag keys
    public enum VoucherTag {
        schemaVersion,   // Protocol version (uint, starts at 1)
        issuerId,        // Issuer identifier
        issuerPubKey,    // Issuer's public key for signature verification
        currency,        // ISO 4217 currency code
        faceValue,       // Value in smallest unit (cents/satoshis)
        issuedAt,        // Unix timestamp of issuance
        expiresAt,       // Unix timestamp of expiry
        merchantId,      // Optional: specific merchant restriction
        termsHash,       // Optional: SHA-256 of terms document
        issuerSig        // Issuer signature over voucher
    }

    // Note: redemptionLimit removed - all vouchers are single-use by default

    // Constructor
    public VoucherSecret() {
        super(Kind.VOUCHER);  // Requires adding VOUCHER to Kind enum
    }

    public VoucherSecret(@NonNull byte[] voucherId) {
        super(Kind.VOUCHER, voucherId);  // voucherId as primary data
    }

    // Tag setters (similar to P2PKSecret pattern)
    public void setSchemaVersion(@NonNull Integer version) {
        setTag(VoucherTag.schemaVersion.name(), List.of(version));
    }

    public void setIssuerId(@NonNull String issuerId) {
        setTag(VoucherTag.issuerId.name(), List.of(issuerId));
    }

    public void setIssuerPubKey(@NonNull String pubKey) {
        setTag(VoucherTag.issuerPubKey.name(), List.of(pubKey));
    }

    public void setCurrency(@NonNull String currency) {
        setTag(VoucherTag.currency.name(), List.of(currency));
    }

    public void setFaceValue(@NonNull Long faceValue) {
        setTag(VoucherTag.faceValue.name(), List.of(faceValue));
    }

    public void setIssuedAt(@NonNull Long timestamp) {
        setTag(VoucherTag.issuedAt.name(), List.of(timestamp));
    }

    public void setExpiresAt(@NonNull Long timestamp) {
        setTag(VoucherTag.expiresAt.name(), List.of(timestamp));
    }

    public void setMerchantId(String merchantId) {
        if (merchantId != null) {
            setTag(VoucherTag.merchantId.name(), List.of(merchantId));
        }
    }

    public void setTermsHash(String hash) {
        if (hash != null) {
            setTag(VoucherTag.termsHash.name(), List.of(hash));
        }
    }

    public void setIssuerSignature(@NonNull String signature) {
        setTag(VoucherTag.issuerSig.name(), List.of(signature));
    }

    // Tag getters (similar to P2PKSecret)
    public Integer getSchemaVersion() {
        Tag tag = getTag(VoucherTag.schemaVersion.name());
        if (tag != null && !tag.getValues().isEmpty()) {
            Object value = tag.getValues().get(0);
            return value instanceof Number n ? n.intValue() : 1;  // Default to v1
        }
        return 1;  // Default to v1 if not specified
    }

    public String getIssuerId() {
        Tag tag = getTag(VoucherTag.issuerId.name());
        return tag != null && !tag.getValues().isEmpty()
            ? String.valueOf(tag.getValues().get(0))
            : null;
    }

    public String getIssuerPubKey() {
        Tag tag = getTag(VoucherTag.issuerPubKey.name());
        return tag != null && !tag.getValues().isEmpty()
            ? String.valueOf(tag.getValues().get(0))
            : null;
    }

    public String getCurrency() {
        Tag tag = getTag(VoucherTag.currency.name());
        return tag != null && !tag.getValues().isEmpty()
            ? String.valueOf(tag.getValues().get(0))
            : null;
    }

    public Long getFaceValue() {
        Tag tag = getTag(VoucherTag.faceValue.name());
        if (tag != null && !tag.getValues().isEmpty()) {
            Object value = tag.getValues().get(0);
            return value instanceof Number n ? n.longValue() : null;
        }
        return null;
    }

    public Long getIssuedAt() {
        Tag tag = getTag(VoucherTag.issuedAt.name());
        if (tag != null && !tag.getValues().isEmpty()) {
            Object value = tag.getValues().get(0);
            return value instanceof Number n ? n.longValue() : null;
        }
        return null;
    }

    public Long getExpiresAt() {
        Tag tag = getTag(VoucherTag.expiresAt.name());
        if (tag != null && !tag.getValues().isEmpty()) {
            Object value = tag.getValues().get(0);
            return value instanceof Number n ? n.longValue() : null;
        }
        return null;
    }

    public String getMerchantId() {
        Tag tag = getTag(VoucherTag.merchantId.name());
        return tag != null && !tag.getValues().isEmpty()
            ? String.valueOf(tag.getValues().get(0))
            : null;
    }

    public String getTermsHash() {
        Tag tag = getTag(VoucherTag.termsHash.name());
        return tag != null && !tag.getValues().isEmpty()
            ? String.valueOf(tag.getValues().get(0))
            : null;
    }

    public String getIssuerSignature() {
        Tag tag = getTag(VoucherTag.issuerSig.name());
        return tag != null && !tag.getValues().isEmpty()
            ? String.valueOf(tag.getValues().get(0))
            : null;
    }

    // Helper method for signature generation
    public byte[] getCanonicalBytesWithoutSignature() {
        // Clone this voucher and remove issuerSig tag
        VoucherSecret copy = this.cloneWithoutSignature();

        // Sort tags by key name for deterministic serialization
        copy.getTags().sort(Comparator.comparing(Tag::getKey));

        // Return JSON bytes
        return copy.toString().getBytes(StandardCharsets.UTF_8);
    }

    private VoucherSecret cloneWithoutSignature() {
        VoucherSecret copy = new VoucherSecret(this.getData());
        copy.setNonce(this.getNonce());

        // Copy all tags except issuerSig
        for (Tag tag : this.getTags()) {
            if (!VoucherTag.issuerSig.name().equals(tag.getKey())) {
                copy.addTag(tag);
            }
        }

        return copy;
    }

    // Deprecated factory for backward compatibility
    @Deprecated(forRemoval = true)
    public static VoucherSecret fromString(@NonNull String jsonString) throws IOException {
        return JsonUtils.JSON_MAPPER.readValue(jsonString, VoucherSecret.class);
    }
}
```

### Field organization

Following the `WellKnownSecret` structure:

**Primary data field (`data`):**
- The voucher ID (UUID bytes or deterministically derived identifier)
- This becomes part of the JSON representation but is separate from tags
- Accessible via `getData()` and `setData()`

**Nonce field:**
- Automatically generated by `WellKnownSecret` constructor
- Ensures uniqueness even if two vouchers have identical metadata
- Random hex string for entropy

**Tags:**
All voucher metadata lives in tags, following the P2PK pattern:
- `schemaVersion` - Integer protocol version (1 for current spec)
- `issuerId` - String identifying the issuing entity
- `issuerPubKey` - Hex-encoded compressed secp256k1 public key (33 bytes)
- `currency` - ISO 4217 currency code ("USD", "EUR", "BTC")
- `faceValue` - Long value in smallest unit (cents for fiat, satoshis for BTC)
- `issuedAt` - Unix timestamp (seconds since epoch)
- `expiresAt` - Unix timestamp for expiry
- `merchantId` - Optional string for merchant-specific vouchers
- `termsHash` - Optional SHA-256 hash of terms document
- `issuerSig` - Hex-encoded ECDSA/Schnorr signature over canonical voucher

**Single-use enforcement:**
All vouchers are single-use. No `redemptionLimit` tag exists. The Cashu spent proof database prevents double-spending automatically.

### Builder pattern example

Provide a fluent builder for wallet integration:

```java
public class VoucherSecretBuilder {
    private byte[] voucherId;
    private String issuerId;
    private String issuerPubKey;
    private String currency;
    private Long faceValue;
    private Long issuedAt;
    private Long expiresAt;
    private String merchantId;
    private String termsHash;

    public VoucherSecretBuilder voucherId(byte[] id) {
        this.voucherId = id;
        return this;
    }

    public VoucherSecretBuilder issuerId(String id) {
        this.issuerId = id;
        return this;
    }

    // ... other fluent setters ...

    public VoucherSecret build(@NonNull PrivateKey issuerKey) {
        // Validate required fields
        Objects.requireNonNull(voucherId, "voucherId required");
        Objects.requireNonNull(issuerId, "issuerId required");
        Objects.requireNonNull(currency, "currency required");
        Objects.requireNonNull(faceValue, "faceValue required");
        Objects.requireNonNull(expiresAt, "expiresAt required");

        // Create voucher
        VoucherSecret voucher = new VoucherSecret(voucherId);
        voucher.setSchemaVersion(1);  // Current protocol version
        voucher.setIssuerId(issuerId);
        voucher.setIssuerPubKey(issuerKey.getPublicKey().toString());
        voucher.setCurrency(currency);
        voucher.setFaceValue(faceValue);
        voucher.setIssuedAt(issuedAt != null ? issuedAt : Instant.now().getEpochSecond());
        voucher.setExpiresAt(expiresAt);
        if (merchantId != null) voucher.setMerchantId(merchantId);
        if (termsHash != null) voucher.setTermsHash(termsHash);

        // Sign the voucher (excluding signature field)
        byte[] canonicalBytes = voucher.getCanonicalBytesWithoutSignature();
        String signature = issuerKey.sign(canonicalBytes).toString();
        voucher.setIssuerSignature(signature);

        return voucher;
    }
}
```

## Wire the secret through the mint workflow

Because `CashuController` and the protocol tasks are parameterised by `T extends Secret`, you introduce the voucher-aware type by:

1. Extending the REST DTOs so that `PostMintRequest<T>` (and related swap/melt requests) are deserialised with your voucher secret. In Spring Boot this usually means registering a `Module` with Jackson to map the request JSON onto your implementation or wrapping the voucher secret inside an existing secret DTO.
2. Ensuring that `MintTokensTask` and `MintTask` receive the new secret without additional casting. They will continue to call the common mint protocol functions, generate blind signatures, and echo your metadata back in the unblinded proofs.
3. Updating wallet-side code to unblind the signatures and reconstruct the voucher secret. The memo payload will survive the round trip, so the redeemer can inspect voucher terms before spending or melting.

If the mint should enforce voucher rules (expiry, issuer allowlist, base currency), add validation hooks before calling `SignBlindedMessageTask.execute()` so malformed or expired voucher secrets yield a structured `CashuErrorException`.

## Define the voucher JSON schema

Since `VoucherSecret` extends `WellKnownSecret`, it inherits the JSON structure with `kind`, `nonce`, `data`, and `tags`. The voucher-specific information lives in the tags.

### Example VoucherSecret JSON

```json
{
  "kind": "VOUCHER",
  "nonce": "a1b2c3d4e5f6g7h8",
  "data": "663961326631662d386433652d346235612d396337662d316532643363346235613666",
  "tags": [
    {"key": "schemaVersion", "values": [1]},
    {"key": "issuerId", "values": ["merchant123"]},
    {"key": "issuerPubKey", "values": ["02a1b2c3d4e5f6789abc..."]},
    {"key": "currency", "values": ["USD"]},
    {"key": "faceValue", "values": [1000000]},
    {"key": "issuedAt", "values": [1705276800]},
    {"key": "expiresAt", "values": [1727366400]},
    {"key": "merchantId", "values": ["shop_xyz"]},
    {"key": "termsHash", "values": ["e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]},
    {"key": "issuerSig", "values": ["3045022100..."]}
  ]
}
```

### Field semantics

**Inherited from WellKnownSecret:**
- `kind`: Always `"VOUCHER"` for voucher secrets
- `nonce`: Random hex string for uniqueness (auto-generated)
- `data`: Voucher ID as **hex-encoded UUID bytes** (16 bytes → 32 hex characters)

**Data field encoding specification:**

The `data` field MUST contain the voucher ID encoded as follows:

1. **UUID representation:** Use standard UUID (16 bytes: 8 bytes MSB + 8 bytes LSB)
2. **Byte order:** Big-endian (most significant bytes first)
3. **Hex encoding:** Convert the 16 bytes to 32 hex characters (lowercase)

**Example:**
```
UUID: f69a1f2c-8d3e-4b5a-9c7f-1e2d3c4b5a6f

Bytes (hex): f69a1f2c 8d3e 4b5a 9c7f 1e2d3c4b5a6f
             └──MSB──┘ └──────────LSB──────────┘

data field: "f69a1f2c8d3e4b5a9c7f1e2d3c4b5a6f"  (32 chars, no hyphens)
```

**Helper implementation:**
```java
// UUID to hex-encoded bytes for data field
public static String uuidToDataField(UUID uuid) {
    ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
    bb.putLong(uuid.getMostSignificantBits());
    bb.putLong(uuid.getLeastSignificantBits());
    return Hex.toHexString(bb.array());  // Returns lowercase hex
}

// Hex-encoded data field back to UUID
public static UUID dataFieldToUuid(String hexData) {
    byte[] bytes = Hex.decode(hexData);
    ByteBuffer bb = ByteBuffer.wrap(bytes);
    return new UUID(bb.getLong(), bb.getLong());
}
```

**Canonical representation guarantees:**
- Same UUID always produces the same data field value
- Different UUIDs always produce different data field values
- Wallets and mints produce identical preimages for the same voucher ID

**Voucher-specific tags:**
- `schemaVersion` - Integer protocol version (always 1 for initial version, increments for breaking changes)
- `issuerId` - String identifier for the issuing entity (merchant/org namespace)
- `issuerPubKey` - Hex-encoded compressed secp256k1 public key (33 bytes, "02..." or "03...")
- `currency` - ISO 4217 currency code ("USD", "EUR", "BTC")
- `faceValue` - Value in smallest unit (cents for fiat, satoshis for BTC) as Number
- `issuedAt` - Unix timestamp (seconds since epoch) when voucher was created
- `expiresAt` - Unix timestamp when voucher becomes invalid
- `merchantId` - (Optional) Specific merchant/service identifier for closed-loop vouchers
- `termsHash` - (Optional) SHA-256 hex hash of off-chain terms document
- `issuerSig` - Hex-encoded ECDSA/Schnorr signature over the canonical voucher (excluding this tag)

**How wallets populate schemaVersion:**
- Wallets creating new vouchers MUST set `schemaVersion` to `1` for the current specification
- Future versions that add/remove/change tag semantics will increment this number
- Wallets MUST query the mint's supported versions (via NUT-06 voucherInfo) before creating vouchers

**How mints validate schemaVersion:**
- Mints MUST check `schemaVersion` during pre-mint validation
- Reject vouchers with unsupported versions (throw `UNSUPPORTED_VOUCHER_VERSION` error)
- Default to version 1 if the tag is missing (for backward compatibility with early implementations)

**Note on single-use enforcement:**
Vouchers are **single-use by default**. The `redemptionLimit` tag has been removed from the specification. Once a voucher proof is spent, it cannot be reused (enforced by the Cashu spent proof database). This design:
- Eliminates redemption tracking complexity
- Improves privacy (no correlation across multiple redemptions)
- Aligns with standard Cashu proof spending semantics
- Simplifies mint validation logic

### Hash-to-curve integration

`VoucherSecret` inherits `toBytes()` from `WellKnownSecret`, which returns the JSON string bytes:

```java
// From WellKnownSecret
@Override
public byte[] toBytes() {
    return this.toString().getBytes(StandardCharsets.UTF_8);
}

// toString() returns full JSON via WellKnownSecretSerializer
@Override
public String toString() {
    try {
        return JsonUtils.JSON_MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
    }
}
```

The hash-to-curve process then works as follows:

```java
// In SecretUtil or BDHKE flow
VoucherSecret voucher = ...; // from wallet or mint

// 1. Convert to bytes (JSON string bytes)
byte[] voucherBytes = voucher.toBytes();

// 2. Hash to curve point
ECPoint Y = BDHKEUtils.hashToCurve(voucherBytes);

// 3. Use for blinding/unblinding
PublicKey curvePoint = PublicKey.fromPoint(Y, true);
```

**Key insight:** Unlike `RandomStringSecret` which hashes raw random bytes, `VoucherSecret` hashes the entire JSON representation. This means all voucher metadata (kind, nonce, data, tags) becomes part of the hash-to-curve input, ensuring the signature binds to the complete voucher structure.

### Deterministic serialization

Jackson serialization is deterministic by default for simple objects, but tags require attention:

**Tag order:** The `tags` list order matters for deterministic hashing. Options:
1. **Lexicographic ordering:** Sort tags by key name before serialization (recommended)
2. **Fixed insertion order:** Always add tags in the same sequence
3. **Map-based tags:** Use a `TreeMap` internally to guarantee sorted output

**Implementation recommendation:**
```java
// In VoucherSecret or WellKnownSecret
public String toCanonicalJson() {
    // Clone the object and sort tags by key
    VoucherSecret sorted = this.clone();
    sorted.getTags().sort(Comparator.comparing(Tag::getKey));
    return JsonUtils.JSON_MAPPER.writeValueAsString(sorted);
}

@Override
public byte[] toBytes() {
    return toCanonicalJson().getBytes(StandardCharsets.UTF_8);
}
```

## Implement validation rules

Mints must enforce voucher constraints before signing blinded messages or accepting proofs for melting. Implement a `VoucherSpendingCondition` that extends `SpendingCondition<VoucherSecret>`.

### Relationship to NUT-11 and NUT-14 validation

Voucher validation has both **overlaps** and **distinctions** compared to P2PK (NUT-11) and HTLC (NUT-14) spending conditions:

**Common patterns (all three):**
- Extend the `SpendingCondition<T>` interface
- Validate during swap/melt operations (not during initial mint)
- Check time-based constraints (locktime in P2PK/HTLC, expiry in vouchers)
- Verify cryptographic signatures (Schnorr in P2PK/HTLC, ECDSA/Schnorr in vouchers)
- Throw `CashuErrorException` for validation failures

**Key differences:**

| Aspect | P2PK (NUT-11) | HTLC (NUT-14) | Vouchers |
|--------|---------------|---------------|----------|
| **Validation timing** | During spending (swap/melt) | During spending (swap/melt) | During spending (swap/melt) |
| **Witness required** | Yes (signatures) | Yes (preimage + signatures) | No (signature in secret itself) |
| **Signature scope** | Proof's secret OR transaction | Preimage + proof's secret | Entire voucher metadata |
| **Signer** | Proof holder (spender) | Proof holder (spender) | Issuer (creator) |
| **Time constraint** | Locktime (enables refund) | Locktime (enables refund) | Expiry (invalidates voucher) |
| **Primary purpose** | Access control | Atomic swaps / Lightning | Issuer authenticity |
| **Multisig support** | Yes (n-of-m) | Yes (via P2PK tags) | No (single issuer) |
| **Pre-spending validation** | None | None | Issuer signature, expiry, allowlist |

**Critical distinction - Validation purpose:**

- **P2PK/HTLC:** Validate that the **spender** has authority to spend the proof (via signatures/preimage in witness)
- **Vouchers:** Validate that the **issuer** had authority to create the voucher (via signature embedded in secret)

**P2PK/HTLC flow:**
```
Wallet creates proof → Mints it (no validation) → Later spends it → Mint validates witness signatures
```

**Voucher flow:**
```
Issuer creates voucher → Signs metadata → Wallet mints it → Mint validates issuer signature → Later spends → Standard proof validation
```

**Hybrid scenario:**

You can combine vouchers with P2PK/HTLC spending conditions:
```java
// A voucher that also requires the recipient's signature to spend
VoucherSecret voucherSecret = new VoucherSecret(voucherId);
voucherSecret.setIssuerId("merchant123");
voucherSecret.setIssuerPubKey(issuerKey.getPublicKey().toString());
// ... set other voucher tags ...

// Wrap in P2PKSecret for recipient-specific locking
P2PKSecret p2pkVoucher = new P2PKSecret(recipientPubKey);
p2pkVoucher.addTag("voucher", voucherSecret.toString());
```

However, this adds complexity. The recommended approach is:
- Use vouchers for **issuer authenticity and metadata**
- Use standard Cashu proofs for **access control** after initial redemption

### The voucher issuer allowlist

**Purpose:** The allowlist determines which voucher issuers a mint trusts and will accept vouchers from.

**Why it exists:**

Without an allowlist, anyone could create vouchers and mint them at your mint. This creates several risks:

1. **Spam/DoS attacks** - Attackers could flood mints with worthless vouchers
2. **Reputation damage** - Accepting fraudulent merchant vouchers harms the mint's credibility
3. **Legal liability** - Mints may be liable for facilitating redemption of fraudulent vouchers
4. **Resource exhaustion** - Storing and validating unlimited issuer keys consumes resources
5. **No economic backing** - Vouchers from unknown issuers have no guarantee of redemption value

**The allowlist provides:**
- **Trust establishment** - Mints explicitly choose which issuers to work with
- **Quality control** - Only vouchers from verified, reputable merchants are accepted
- **Business relationships** - Enables commercial agreements between mints and issuer networks
- **Spam prevention** - Rejects vouchers from unauthorized sources
- **User protection** - Wallets know which issuers this mint supports before creating vouchers

### Who maintains the allowlist?

**Each mint operator maintains their own allowlist.** This is a critical trust and business decision:

**Option 1: Mint-curated allowlist (recommended for most mints)**

The mint operator manually curates a list of trusted issuers:

```java
// In mint configuration
public class VoucherConfig {
    private Set<String> allowedIssuers = Set.of(
        "merchant123",      // Coffee shop network
        "retail-xyz",       // Retail voucher platform
        "airline-abc"       // Airline gift cards
    );

    private Map<String, PublicKey> issuerPublicKeys = Map.of(
        "merchant123", PublicKey.fromString("02abc123..."),
        "retail-xyz", PublicKey.fromString("03def456..."),
        "airline-abc", PublicKey.fromString("02ghi789...")
    );
}
```

**How mints populate this:**
1. **Business relationships** - Mint operators negotiate with voucher issuers (merchants, platforms)
2. **Due diligence** - Verify issuer identity, business legitimacy, redemption infrastructure
3. **Nostr discovery** - Query kind:38174 events and kind:38001 recommendations
4. **Manual addition** - Mint operator adds issuer ID and public key to configuration
5. **Ongoing monitoring** - Track voucher redemption rates, fraud incidents, issuer reputation

**Option 2: Community-driven allowlist (federated mints)**

Mints can share allowlists via Nostr (NIP-51 lists):

```java
// Mint publishes its allowlist on Nostr
NostrEvent allowlistEvent = NostrEvent.builder()
    .kind(30078)
    .pubkey(mintPubkey)
    .tags(List.of(
        Tag.of("d", "voucher-issuer-allowlist"),
        Tag.of("a", "38174:<issuer-1-pubkey>:<issuer-1-id>"),
        Tag.of("a", "38174:<issuer-2-pubkey>:<issuer-2-id>")
    ))
    .build();

// Other mints can query and import trusted issuers
```

Benefits:
- Network effects (more mints = more issuer coverage)
- Shared due diligence costs
- Faster onboarding of new issuers

Risks:
- Less control over which issuers are accepted
- Potential for allowlist spam/pollution
- Dependency on federation governance

**Option 3: Web-of-trust (decentralized)**

Mints build allowlists from recommendations:

```java
// Query recommendations from trusted entities
List<NostrEvent> recommendations = nostrClient.query(Filter.builder()
    .kinds(38001)  // Voucher issuer recommendations
    .authors(trustedValidators)  // Trusted mint operators, auditors
    .build());

// Build allowlist from highly-recommended issuers
Set<String> allowlist = recommendations.stream()
    .filter(rec -> rec.getTagCount("rating") >= 4)  // 4+ stars
    .map(rec -> rec.getFirstTag("d").getValue())
    .collect(Collectors.toSet());
```

Benefits:
- Leverages social proof
- Decentralized trust model
- Scales with ecosystem growth

Risks:
- Sybil attacks (fake recommendations)
- Slower to update
- Requires trusted validator network

**Option 4: Open/permissionless (not recommended)**

Mint accepts vouchers from any issuer:

```java
// No allowlist - accept all issuers
public boolean isIssuerAllowed(String issuerId) {
    return true;  // ⚠️ Dangerous!
}
```

**Only use this for:**
- Testing/development environments
- Proof-of-concept deployments
- Private mints with trusted user bases

**Never use in production** due to spam, fraud, and liability risks.

### Recommended approach: Hybrid model

**Best practice for production mints:**

1. **Start with curated allowlist** - Manually add 5-10 trusted issuers
2. **Publish on Nostr** - Make your allowlist discoverable (NIP-51)
3. **Monitor recommendations** - Track kind:38001 events for new issuers
4. **Gradual expansion** - Add issuers based on recommendations + due diligence
5. **Periodic review** - Remove inactive or problematic issuers

```java
public class HybridAllowlistManager {

    private Set<String> coreIssuers;  // Manual curation
    private Map<String, Integer> recommendedIssuers;  // From Nostr

    public boolean isIssuerAllowed(String issuerId) {
        // Always allow core issuers
        if (coreIssuers.contains(issuerId)) {
            return true;
        }

        // Allow recommended issuers above threshold
        Integer recommendationCount = recommendedIssuers.get(issuerId);
        return recommendationCount != null && recommendationCount >= 5;
    }

    public void updateFromNostr() {
        // Periodically query Nostr for new recommendations
        List<NostrEvent> recs = nostrClient.queryRecommendations();

        // Build recommendation scores
        for (NostrEvent rec : recs) {
            String issuerId = rec.getFirstTag("d").getValue();
            recommendedIssuers.merge(issuerId, 1, Integer::sum);
        }
    }
}
```

### Allowlist publication and discovery

**For transparency, mints should publish their allowlists:**

1. **Via NUT-06 mint info:**
```json
{
  "voucherInfo": {
    "supported": true,
    "allowedIssuers": "https://mint.example.com/voucher/issuers",
    "nostrAllowlist": "30078:<mint-pubkey>:voucher-issuer-allowlist"
  }
}
```

2. **Via Nostr (NIP-51):**
```json
{
  "kind": 30078,
  "pubkey": "<mint-pubkey>",
  "tags": [
    ["d", "voucher-issuer-allowlist"],
    ["a", "38174:<issuer-1-pubkey>:<issuer-1-id>"],
    ["a", "38174:<issuer-2-pubkey>:<issuer-2-id>"]
  ]
}
```

3. **Via HTTP endpoint:**
```json
// GET https://mint.example.com/v1/voucher/issuers
{
  "issuers": [
    {
      "id": "merchant123",
      "name": "Coffee Shop Network",
      "pubkey": "02abc123...",
      "addedAt": 1705276800,
      "status": "active"
    }
  ]
}
```

**Wallets can then:**
- Check if a voucher's issuer is accepted before attempting to mint
- Display which mints support which issuers
- Guide users to appropriate mints for their vouchers

### Pre-mint validation

Before calling `SignBlindedMessageTask.execute()`:

**Schema validation:**
- Verify `schemaVersion` is supported (reject unknown versions).
- Confirm all required fields are present and non-null.
- Check field value ranges (e.g., `faceValue > 0`, `expiresAt > issuedAt`).

**Integrity checks:**
- Verify `issuerSignature` using `issuerPubKey` and the canonical JSON encoding of all tags except `issuerSig`.
- Confirm `issuerPubKey` is on the issuer allowlist (if the mint restricts voucher issuers).
- Validate `termsHash` matches the hash of the referenced terms document (if applicable).

**Note on canonical encoding for signature verification:**
The signature covers the JSON representation with tags sorted by key name, excluding the `issuerSig` tag itself. This is computed by `getCanonicalBytesWithoutSignature()` which:
1. Clones the VoucherSecret and removes the `issuerSig` tag
2. Sorts all remaining tags by key name (lexicographic order)
3. Serializes to JSON and converts to UTF-8 bytes

**Expiry enforcement:**
- Reject if `expiresAt < currentTimestamp()`.
- Optionally enforce a buffer period (e.g., reject vouchers expiring within 1 hour to prevent race conditions).

**Currency and amount:**
- Verify `currency` matches the mint's supported currencies (or convert using NUT-00 mint info).
- Ensure `faceValue` matches the sum of blinded message amounts in the mint request.

**Single-use enforcement:**
- Vouchers are single-use by default (no `redemptionLimit` tag).
- The Cashu spent proof database automatically prevents double-spending.
- No additional tracking required beyond standard proof validation.

**Example validation logic:**
```java
public class VoucherSpendingCondition implements SpendingCondition<VoucherSecret> {

    @Override
    public void validate(Proof<VoucherSecret> proof, ValidationContext ctx)
            throws CashuErrorException {

        VoucherSecret secret = proof.getSecret();

        // Schema version check
        if (secret.getSchemaVersion() > SUPPORTED_VERSION) {
            throw new CashuErrorException(
                CashuErrorCode.UNSUPPORTED_VOUCHER_VERSION,
                "Voucher schema version " + secret.getSchemaVersion() + " not supported"
            );
        }

        // Expiry check
        if (secret.getExpiresAt() < Instant.now().getEpochSecond()) {
            throw new CashuErrorException(
                CashuErrorCode.VOUCHER_EXPIRED,
                "Voucher expired at " + secret.getExpiresAt()
            );
        }

        // Issuer signature verification
        if (!verifyIssuerSignature(secret)) {
            throw new CashuErrorException(
                CashuErrorCode.INVALID_ISSUER_SIGNATURE,
                "Voucher issuer signature invalid"
            );
        }

        // Issuer allowlist check
        if (!ctx.getAllowedIssuers().contains(secret.getIssuerId())) {
            throw new CashuErrorException(
                CashuErrorCode.ISSUER_NOT_ALLOWED,
                "Issuer " + secret.getIssuerId() + " not in allowlist"
            );
        }

        // Currency check
        if (!ctx.getSupportedCurrencies().contains(secret.getCurrency())) {
            throw new CashuErrorException(
                CashuErrorCode.CURRENCY_NOT_SUPPORTED,
                "Currency " + secret.getCurrency() + " not supported"
            );
        }

        // Single-use enforcement is automatic via spent proof database
        // No additional redemption tracking needed
    }

    private boolean verifyIssuerSignature(VoucherSecret secret) {
        byte[] message = secret.getCanonicalBytesWithoutSignature();
        return ECDSAUtil.verify(
            secret.getIssuerPubKey(),
            message,
            secret.getIssuerSignature()
        );
    }
}
```

### Error codes

Define voucher-specific error codes in `CashuErrorCode`:

| Code | Value | Description |
|------|-------|-------------|
| `UNSUPPORTED_VOUCHER_VERSION` | 40000 | Schema version not supported by this mint |
| `VOUCHER_EXPIRED` | 40001 | Voucher expiry timestamp has passed |
| `INVALID_ISSUER_SIGNATURE` | 40002 | Issuer signature verification failed |
| `ISSUER_NOT_ALLOWED` | 40003 | Issuer not in mint's allowlist |
| `CURRENCY_NOT_SUPPORTED` | 40004 | Voucher currency not accepted by mint |
| `VOUCHER_AMOUNT_MISMATCH` | 40005 | Face value doesn't match mint request amounts |
| `TERMS_HASH_INVALID` | 40006 | Terms hash doesn't match referenced document |

**Note:** `REDEMPTION_LIMIT_EXCEEDED` removed - all vouchers are single-use, enforced by spent proof database.

## Handle schema versioning

### Version evolution strategy

**Version 1 (initial):** The schema defined above with all core and optional fields.

**Future versions:** Add new optional tags to maintain backward compatibility. Never remove or reorder existing tag keys. Tag addition is safe; tag removal or semantic changes require incrementing schemaVersion.

**Example version 2 additions:**

If future versions need additional constraints, add new tags:
```json
{
  "kind": "VOUCHER",
  "tags": [
    // ... existing v1 tags ...
    {"key": "geoRestriction", "values": [{"lat": 40.7128, "lon": -74.0060, "radius": 5000}]},
    {"key": "productCategories", "values": ["coffee", "pastries"]},
    {"key": "nftTokenId", "values": ["ethereum:0xabc123..."]}
  ]
}
```

**Important:** Do not add multi-use or redemption tracking to future versions. Maintain single-use enforcement for privacy and simplicity.

### Mint compatibility matrix

Publish a compatibility document in NUT-06 mint info or `/voucher/schema` endpoint:

```json
{
  "supportedSchemaVersions": [1, 2],
  "currentVersion": 2,
  "deprecatedVersions": [],
  "schemaUrl": "https://mint.example.com/.well-known/voucher-schema-v2.json",
  "cborSpecUrl": "https://mint.example.com/docs/voucher-cbor-encoding"
}
```

**Deprecation policy:**
- Mints should support at least N-2 versions (current and previous two).
- Announce deprecation at least 6 months before removing support.
- Return `UNSUPPORTED_VOUCHER_VERSION` for unsupported versions with a link to migration docs.

### Wallet version handling

Wallets should:
1. Parse the `schemaVersion` field before attempting to decode the rest of the voucher.
2. Maintain decoders for all schema versions they've ever created vouchers in (to support restore operations).
3. Display a warning if a received voucher uses a newer schema version than the wallet supports.
4. When minting new vouchers, use the highest schema version supported by both wallet and mint.

## Wire through the REST and protocol layers

### Extend WellKnownSecretDeserializer

The existing polymorphic deserialization infrastructure in cashu-lib already handles `WellKnownSecret` subclasses. You only need to:

1. **Add `VOUCHER` to the `WellKnownSecret.Kind` enum** (in cashu-lib):
```java
public enum Kind {
    P2PK,
    HTLC,
    VOUCHER  // Add this
}
```

2. **Extend `WellKnownSecretDeserializer`** to handle the VOUCHER kind:
```java
// In cashu-lib WellKnownSecretDeserializer.java
public class WellKnownSecretDeserializer extends JsonDeserializer<WellKnownSecret> {

    @Override
    public WellKnownSecret deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        WellKnownSecretDTO dto = mapper.readValue(p, WellKnownSecretDTO.class);

        // Kind-based instantiation
        WellKnownSecret secret = switch (dto.getKind()) {
            case P2PK -> new P2PKSecret();
            case VOUCHER -> new VoucherSecret();  // Add this case
            default -> throw new IllegalArgumentException("Invalid kind: " + dto.getKind());
        };

        // Initialize fields
        secret.setNonce(dto.getNonce());
        if (dto.getData() != null) {
            secret.setData(Hex.decode(dto.getData()));
        }

        // Tag conversion with type-specific handling
        if (dto.getTags() != null) {
            for (WellKnownSecret.Tag tag : dto.getTags()) {
                convertTagValues(tag, dto.getKind());  // Convert based on kind
                secret.addTag(tag);
            }
        }
        return secret;
    }

    private void convertTagValues(WellKnownSecret.Tag tag, WellKnownSecret.Kind kind) {
        if (kind == Kind.P2PK) {
            convertP2PKTagValues(tag);  // Existing P2PK logic
        } else if (kind == Kind.VOUCHER) {
            convertVoucherTagValues(tag);  // Add voucher-specific logic
        }
    }

    private void convertVoucherTagValues(WellKnownSecret.Tag tag) {
        // Type-safe conversion for voucher tags
        switch (tag.getKey()) {
            case "faceValue", "issuedAt", "expiresAt" -> {
                // Number tags -> Long
                List<Object> values = new ArrayList<>();
                for (Object v : tag.getValues()) {
                    if (v instanceof Number n) {
                        values.add(n.longValue());
                    } else {
                        values.add(v);
                    }
                }
                tag.setValues(values);
            }
            case "issuerId", "issuerPubKey", "currency", "merchantId", "termsHash", "issuerSig" -> {
                // String tags - ensure String type
                List<Object> values = new ArrayList<>();
                for (Object v : tag.getValues()) {
                    values.add(String.valueOf(v));
                }
                tag.setValues(values);
            }
        }
    }
}
```

3. **No Spring configuration needed** - The existing `@JsonDeserialize(using = SecretDeserializer.class)` annotation on `Proof.secret` already routes object secrets to `WellKnownSecretDeserializer`, which now handles VOUCHER kind.

### Automatic polymorphic handling

The existing `SecretDeserializer` already provides the entry point:

```java
// Already in cashu-lib - no changes needed
public class SecretDeserializer extends JsonDeserializer<Secret> {
    @Override
    public Secret deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();

        if (node.isTextual()) {
            // "abc123..." -> RandomStringSecret
            return RandomStringSecret.fromString(node.textValue());
        }

        if (node.isObject()) {
            // {"kind": "VOUCHER", ...} -> VoucherSecret (via WellKnownSecretDeserializer)
            // {"kind": "P2PK", ...} -> P2PKSecret
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            return mapper.treeToValue(node, WellKnownSecret.class);
        }

        throw new RuntimeException("Invalid Secret format");
    }
}
```

So when a wallet sends:
```json
{
  "quote": "quote-uuid",
  "outputs": [...],
  "secrets": [
    {
      "kind": "VOUCHER",
      "nonce": "...",
      "data": "...",
      "tags": [...]
    }
  ]
}
```

The deserialization flow is:
1. `SecretDeserializer` sees an object → delegates to `WellKnownSecretDeserializer`
2. `WellKnownSecretDeserializer` reads the `kind` field → instantiates `VoucherSecret()`
3. Tags are populated with type conversion
4. Result is a fully hydrated `VoucherSecret` instance

### Request flow example

**Wallet mints a voucher:**

1. Wallet creates `VoucherSecret` with all metadata fields.
2. Wallet blinds the preimage using standard BDHKE: `B = r*G + hash_to_curve(preimage)`.
3. Wallet constructs `PostMintRequest`:
```json
{
  "quote": "quote-uuid",
  "outputs": [
    {
      "amount": 1000000,
      "id": "keyset-id",
      "B_": "02a1b2c3d4e5f6..."  // blinded message
    }
  ]
}
```
4. Wallet stores the voucher metadata and blinding factor `r` locally.

**Mint processes the request:**

1. `CashuController.mint()` receives `PostMintRequest<VoucherSecret>`.
2. `NUT04.mint()` calls `MintTask.execute()` with the blinded messages.
3. `MintTask` iterates through outputs and calls `SignBlindedMessageTask` for each.
4. Before signing, `VoucherSpendingCondition` validates the voucher (if the mint has pre-mint validation enabled).
5. `SignBlindedMessageTask` computes the blind signature: `C' = k*B'` where `k` is the mint's private key.
6. Mint returns `PostMintResponse` with blind signatures.

**Wallet unblinds the signature:**

1. Wallet receives `C'` and removes the blinding factor: `C = C' - r*K` where `K` is the mint's public key.
2. Wallet constructs the final proof with unblinded signature `C` and the full `VoucherSecret`.
3. Wallet can now display voucher metadata (expiry, face value, issuer) from the secret.

**Wallet spends/melts the voucher:**

1. Wallet creates `PostSwapRequest` or `PostMeltRequest` with `Proof<VoucherSecret>`.
2. Mint validates the proof signature using BDHKE: verify `C = k*hash_to_curve(preimage)`.
3. Mint's `VoucherSpendingCondition` enforces expiry, redemption limits, and issuer rules.
4. If valid, mint processes the swap/melt and marks the proof as spent.

### Database persistence

When storing proofs in the database, the `VoucherSecret.toString()` method returns the JSON string representation:

```java
// In MintProtocolUtil.java
ProofEntity entity = ProofEntity.builder()
    .amount(proof.getAmount())
    .secret(proof.getSecret().toString())  // Stores JSON string
    .C(proof.getUnblindedSignature().toString())
    .keySetId(proof.getKeySetId())
    .build();
```

**What toString() returns:**
For `VoucherSecret` (which extends `WellKnownSecret`), `toString()` returns the complete JSON representation via Jackson:

```json
{
  "kind": "VOUCHER",
  "nonce": "...",
  "data": "...",
  "tags": [
    {"key": "schemaVersion", "values": [1]},
    {"key": "issuerId", "values": ["..."]},
    ...
  ]
}
```

When restoring, deserialize from the stored JSON string:

```java
// The existing SecretDeserializer handles this automatically
Secret secret = JsonUtils.JSON_MAPPER.readValue(
    entity.getSecret(),
    Secret.class  // Polymorphic deserialization to VoucherSecret
);
```

## Implementation impact across repositories

Rolling out vouchers touches multiple codebases; coordinating the changes avoids protocol drift and keeps wallet–mint interoperability tight.

### cashu-lib changes

- Extend `WellKnownSecret.Kind` with `VOUCHER`, register the subtype in `WellKnownSecretDeserializer`, and expose voucher-aware tag conversion.
- Provide the `VoucherSecret` implementation (or equivalent) with deterministic `toBytes()`, schema-aware getters/setters, and `getCanonicalBytesWithoutSignature()` for signing.
- Add reusable validation helpers (schema version, issuer signature verification, hash-to-curve utilities) plus voucher-specific error codes that mints and clients can share.
- Ensure the serializer sorts tags or otherwise guarantees canonical JSON so every participant hashes and signs identical byte sequences.
- Ship voucher test vectors in the shared test suite to prove serialization and BDHKE compatibility across languages and platforms.

### cashu-mint changes

- Update request/response DTOs and persistence entities so Jackson deserializes voucher secrets transparently and stored proofs retain the JSON form.
- Register a `VoucherSpendingCondition` (pre-mint and pre-swap) that enforces schema version, expiry, issuer allowlist, supported currencies, and signature verification.
- Expose operator configuration for issuer allowlists, schema support windows, optional expiry buffers, and terms-hash enforcement.
- Publish voucher capability metadata through NUT-06 responses, `.well-known` endpoints, and any Nostr discovery feeds so wallets can discover mint policy.
- Expand integration tests to cover voucher mint/swap/melt flows, including rejection paths for unsupported schema versions or untrusted issuers.

### cashu-client changes

- Teach wallet flows to build `VoucherSecret` instances, set the `schemaVersion` tag, sign them with issuer keys, and blind the resulting secrets before minting.
- Persist the richer metadata locally so UX can surface issuer, value, expiry, and policy disclaimers prior to redemption.
- Validate mint compatibility by checking the advertised voucher schema versions and issuer allowlists; fail fast or guide users to supported mints.
- Update backup/restore and proof import/export routines to preserve structured secrets verbatim and add regression tests around canonical JSON ordering.

### Optional cashu-voucher module

If voucher functionality grows, isolating it in a dedicated `cashu-voucher` module can keep the base libraries slimmer:

- **Scope:** Host the `VoucherSecret` type, canonicalization helpers, issuer signature utilities, schema definitions, and shared validation logic. Expose thin adapters for mint and client runtimes.
- **Dependencies:** Depend on `cashu-lib` for core interfaces (`Secret`, `WellKnownSecret`, crypto primitives) while avoiding framework-specific code so both server and wallet stacks can consume it.
- **Consumers:** Let `cashu-mint` and `cashu-client` import `cashu-voucher` instead of duplicating helpers, guaranteeing identical tag handling, error codes, and test vectors across the ecosystem.
- **Release cadence:** Version the module alongside `cashu-lib` (and document compatibility) so a single upgrade delivers synchronized schema support to wallets and mints.

The separate module is optional, but it reduces duplication and gives issuers, wallets, and mints a single source of truth for voucher logic.

## Security considerations

### Issuer signature verification

**Critical:** Always verify the `issuerSignature` before accepting a voucher. An attacker could create vouchers with fake issuer IDs if signature verification is skipped.

**Signature scope:** The signature must cover all tags except the `issuerSig` tag itself. Use canonical JSON encoding (sorted tags) to prevent signature malleability.

**Key management:** Issuers should rotate signing keys periodically and publish their public keys in a well-known location (e.g., `https://issuer.example.com/.well-known/voucher-pubkeys.json`). Mints can cache issuer public keys and update them daily.

### Replay prevention

The Cashu protocol already prevents proof replay via the spent proof database. However, vouchers add a new attack vector: **preimage reuse**.

**Risk:** If an issuer reuses the same `preimage` for multiple vouchers (with different metadata), an attacker could substitute one voucher's metadata for another after minting.

**Mitigation:**
- Issuers must generate a fresh cryptographic-quality random `preimage` for every voucher.
- Issuers should include the `preimage` in the signature scope by hashing it into the message.
- Mints can optionally track `(preimage, voucherId)` pairs to detect reuse.

### Expiry and time synchronization

**Clock skew:** Wallets and mints may have different system times. Use a grace period (e.g., ±5 minutes) when checking expiry to prevent edge-case failures.

**Timezone handling:** Always use Unix timestamps (seconds since epoch, UTC) to avoid timezone ambiguity.

**NTP synchronization:** Recommend that mints run NTP clients to keep accurate time.

### Privacy implications

**Voucher metadata is visible to the mint:** Unlike the blinded preimage, all voucher metadata (issuer, face value, expiry) is visible to the mint during validation. This is necessary for enforcement but reduces privacy compared to plain Cashu ecash.

**Mitigation strategies:**
- Use disposable issuer IDs that don't reveal merchant identity.
- Set broad expiry windows (e.g., 1 month) rather than precise timestamps.
- Aggregate vouchers into larger denominations to reduce metadata granularity.

**Wallet privacy:** Wallets should not share voucher metadata with third parties without user consent. Display clear warnings when vouchers contain identifiable information.

### Double-spending prevention

**Single-use enforcement:**
All vouchers are single-use by design. The Cashu spent proof database prevents double-spending automatically through the standard proof verification flow:

1. When a voucher proof is spent (swap/melt), the mint adds the proof's hash to the spent proof database
2. Any subsequent attempt to spend the same proof is rejected (standard Cashu behavior)
3. No additional voucher-specific tracking required

**Privacy benefits:**
- No correlation across multiple redemptions (because there are none)
- No redemption history tracking needed
- Aligns with Cashu's privacy-preserving design
- Simpler mint implementation

**Splitting vouchers:**
If users need to split a voucher (e.g., redeem $50 from a $100 voucher):
1. Use the standard Cashu swap operation
2. Spend the original $100 voucher proof
3. Receive new proofs totaling $100 (can be standard RandomStringSecret proofs, not vouchers)
4. The voucher has fulfilled its purpose; further transactions are regular ecash

## Versioning and interoperability

### Schema publication

Publish the voucher schema alongside your mint metadata so third-party wallets can construct compatible structured secrets.

**NUT-06 extension:**
Add a `voucherInfo` field to the mint info response:

```json
{
  "name": "Example Mint",
  "pubkey": "02a1b2c3d4e5f6...",
  "version": "cashu-mint/1.0.0",
  "voucherInfo": {
    "supported": true,
    "schemaVersions": [1],
    "cborSpecUrl": "https://mint.example.com/docs/voucher-cbor-spec",
    "allowedIssuers": "https://mint.example.com/voucher/issuers",
    "validationRules": {
      "enforceExpiry": true,
      "enforceIssuerSignature": true,
      "requireIssuerAllowlist": true,
      "supportedCurrencies": ["USD", "EUR", "BTC"]
    }
  }
}
```

**Issuer registry on Nostr:**

Following the pattern established by **NIP-87 (Ecash Mint Discoverability)**, maintain the issuer registry on the Nostr network using similar event structures.

### Recommended Approach: NIP-87 Pattern

Define new event kinds for voucher issuer announcements and recommendations:

**kind:38174** - Voucher Issuer Announcement (analogous to NIP-87's kind:38172 for Cashu mints)
**kind:38001** - Voucher Issuer Recommendation (analogous to NIP-87's kind:38000)

### Issuer Announcement Structure (kind:38174)

Voucher issuers publish announcements containing their metadata:

```json
{
  "kind": 38174,
  "pubkey": "02a1b2c3d4e5f6...",
  "created_at": 1705276800,
  "tags": [
    ["d", "merchant123"],
    ["u", "https://issuer.example.com"],
    ["name", "Coffee Shop Network"],
    ["description", "Gift vouchers for 500+ coffee shops worldwide"],
    ["currency", "USD"],
    ["currency", "EUR"],
    ["currency", "BTC"],
    ["category", "food-beverage"],
    ["category", "retail"],
    ["n", "mainnet"],
    ["contact", "support@coffeeshop.example.com"],
    ["nip05", "vouchers@coffeeshop.example.com"],
    ["image", "https://coffeeshop.example.com/logo.png"],
    ["terms", "https://coffeeshop.example.com/voucher-terms"],
    ["relay", "wss://relay.coffeeshop.example.com"]
  ],
  "content": "{\"merchantCount\":532,\"supportedMints\":[\"https://mint1.example.com\",\"https://mint2.example.com\"]}"
}
```

**Key design decision: Single key for both Nostr and voucher signing**

The issuer's Nostr keypair (`pubkey` field) serves dual purposes:
1. Signs the Nostr announcement event (standard Nostr behavior)
2. Signs individual vouchers (the `issuerPubKey` in VoucherSecret matches this pubkey)

**Rationale for single-key approach:**
- **Stronger identity binding:** Vouchers are cryptographically tied to the issuer's Nostr identity
- **Simpler key management:** One keypair to secure instead of two
- **Leverages Nostr infrastructure:** NIP-05 verification, social graph trust, web-of-trust
- **Natural key rotation:** Update via replaceable events (see [Issuer Key Rotation](#issuer-key-rotation))
- **Eliminates trust bootstrapping:** No need to verify out-of-band that a separate signing key belongs to the issuer

**Alternative: Separate voucher signing key (not recommended)**

If operational security requires isolating the hot voucher signing key from the cold Nostr identity key, add a `signing-key` tag:

```json
{
  "kind": 38174,
  "pubkey": "02nostr-identity...",
  "tags": [
    ["d", "merchant123"],
    ["signing-key", "03hot-voucher-key...", "active"],
    ["signing-key", "02deprecated-key...", "deprecated", "1735689600"],
    // ... other tags
  ]
}
```

This allows:
- Multiple signing keys under one identity (for delegation or rotation)
- Hot/cold wallet separation (Nostr key in cold storage, voucher key in hot wallet)
- Algorithm flexibility (though both Nostr and secp256k1 ECDSA are compatible)

**However**, this adds complexity:
- How do mints verify the `signing-key` tag is legitimate? They must trust the Nostr event signature.
- Key rotation requires updating and re-signing the announcement event anyway.
- No additional security benefit if the Nostr key can sign arbitrary signing-key assignments.

**Recommended approach:** Use the single-key design unless you have specific operational requirements for key separation. The VoucherSecret's `issuerPubKey` field should match the Nostr event's `pubkey`.

**Key tags:**
- `d` - Issuer identifier (matches `issuerId` in VoucherSecret)
- `u` - Issuer website/API URL
- `name` - Human-readable issuer name
- `description` - Issuer description and voucher purpose
- `currency` - Supported currencies (multiple allowed)
- `category` - Voucher categories for filtering (food-beverage, retail, travel, etc.)
- `n` - Network (mainnet, testnet, signet)
- `contact` - Support contact email
- `nip05` - NIP-05 identifier for verification
- `image` - Logo URL
- `terms` - URL to voucher terms and conditions
- `relay` - Recommended relay for issuer updates

**Content field:** Optional JSON with additional metadata (supported mints, merchant count, service details)

**Issuer pubkey (event-level field):** The secp256k1 public key used to:
1. Sign this Nostr event (proves the issuer controls this identity)
2. Sign individual vouchers (the `issuerPubKey` in VoucherSecret should match this value)

### Issuer Recommendation Structure (kind:38001)

Users and mints publish recommendations to build trust networks:

```json
{
  "kind": 38001,
  "pubkey": "<recommender-pubkey>",
  "created_at": 1705276900,
  "tags": [
    ["k", "38174"],
    ["d", "merchant123"],
    ["u", "https://coffeeshop.example.com"],
    ["a", "38174:<issuer-pubkey>:merchant123", "wss://relay.example.com"],
    ["rating", "5"]
  ],
  "content": "Trusted voucher issuer. Redeemed 50+ vouchers without issues."
}
```

**Key tags:**
- `k` - References the announcement kind (38174)
- `d` - Issuer identifier being recommended
- `u` - Issuer URL (optional, for convenience)
- `a` - Address pointer to the issuer's kind:38174 event with relay hint
- `rating` - Optional 1-5 star rating

### Discovery Flow

**Mint-side discovery:**

1. **Query for recommendations** from trusted entities (mint operators, known validators):
```
REQ ["voucher-issuer-recs", {
  "kinds": [38001],
  "authors": [<trusted-pubkey-list>],
  "#k": ["38174"]
}]
```

2. **Fetch issuer details** using the `a` tags from recommendations:
```
REQ ["voucher-issuer-info", {
  "kinds": [38174],
  "#d": ["merchant123", "shop-xyz", ...]
}]
```

3. **Build issuer allowlist** from discovered issuers:
```java
List<VoucherIssuer> allowedIssuers = nostrEvents.stream()
    .filter(event -> event.getKind() == 38174)
    .map(this::parseIssuerAnnouncement)
    .collect(Collectors.toList());
```

**Wallet-side discovery:**

1. **Direct query for all issuers** (requires spam-resistant relays):
```
REQ ["all-voucher-issuers", {
  "kinds": [38174],
  "#n": ["mainnet"],
  "#currency": ["USD"]
}]
```

2. **Filter by category**:
```
REQ ["food-vouchers", {
  "kinds": [38174],
  "#category": ["food-beverage"]
}]
```

3. **Discover via social graph** (query recommendations from follows):
```
REQ ["trusted-voucher-issuers", {
  "kinds": [38001],
  "authors": [<user-follows>]
}]
```

### Issuer Key Rotation

**With single-key design (recommended):**

When issuers need to rotate their Nostr/voucher signing key, they publish a new kind:38174 event with the new keypair:

```json
{
  "kind": 38174,
  "pubkey": "03newkey123...",  // New Nostr keypair
  "created_at": 1735689600,
  "tags": [
    ["d", "merchant123"],  // Same issuer ID
    ["deprecated-key", "02oldkey456...", "1767225600"],  // Old key + grace period end
    // ... other tags remain the same
  ]
}
```

**Migration process:**
1. Issuer generates new keypair
2. Publishes new kind:38174 event with new `pubkey` and old key in `deprecated-key` tag
3. Stops signing new vouchers with old key immediately
4. Grace period allows existing vouchers signed with old key to be redeemed

**Mint behavior:**
- Accept vouchers signed by keys listed in `deprecated-key` tags until their expiry timestamp
- Validate new vouchers only against the current event's `pubkey` field
- Query Nostr relays periodically for issuer event updates (replaceable event type)
- Cache issuer events with a reasonable TTL (e.g., 24 hours)

**Wallet behavior:**
- When creating new vouchers, always use the latest issuer pubkey from their most recent kind:38174 event
- Display warning to users if redeeming a voucher signed with a deprecated key near expiry

**With separate signing keys (if using the alternative approach):**

Add multiple `signing-key` tags with status indicators:

```json
{
  "kind": 38174,
  "pubkey": "02nostr-identity...",  // Nostr identity (unchanged)
  "tags": [
    ["d", "merchant123"],
    ["signing-key", "03newkey...", "active", "1735689600"],  // New active key + activation date
    ["signing-key", "02oldkey...", "deprecated", "1767225600"],  // Old key + expiry
    // ... other tags
  ]
}
```

Mints validate vouchers against any `signing-key` tag with:
- `active` status, OR
- `deprecated` status where current time < expiry timestamp

### Alternative: NIP-51 Curated Lists

Mints can also maintain curated issuer lists using **NIP-51 (Lists)**:

**kind:30078** - Custom application-specific set (voucher issuer allowlist)

```json
{
  "kind": 30078,
  "pubkey": "<mint-pubkey>",
  "tags": [
    ["d", "voucher-issuer-allowlist"],
    ["title", "Trusted Voucher Issuers"],
    ["description", "Voucher issuers accepted by this mint"],
    ["a", "38174:<issuer-pubkey-1>:<issuer-id-1>", "wss://relay1"],
    ["a", "38174:<issuer-pubkey-2>:<issuer-id-2>", "wss://relay2"],
    ["a", "38174:<issuer-pubkey-3>:<issuer-id-3>", "wss://relay3"]
  ]
}
```

Wallets can discover which mints accept which issuers by querying these lists.

### Combined Strategy

**Best practice:** Use both approaches
1. **NIP-87 pattern (kind:38174/38001)** for global issuer discovery and reputation
2. **NIP-51 lists (kind:30078)** for mint-specific allowlists and policies

This provides:
- **Decentralized discovery** via social recommendations
- **Explicit mint policies** via public allowlists
- **Trust verification** through cross-referencing recommendations
- **Spam resistance** by querying trusted authors and curated lists

### Wallet integration guide

Provide a reference implementation or SDK for wallet developers:

**Minting flow:**
```java
// 1. Generate voucher ID
UUID voucherId = UUID.randomUUID();
// e.g., f69a1f2c-8d3e-4b5a-9c7f-1e2d3c4b5a6f

// Convert UUID to 16 bytes for the data field
byte[] voucherIdBytes = uuidToBytes(voucherId);

// 2. Create VoucherSecret with all metadata
VoucherSecret secret = new VoucherSecret(voucherIdBytes);
// data field will be hex-encoded: "f69a1f2c8d3e4b5a9c7f1e2d3c4b5a6f"
secret.setSchemaVersion(1);  // Current protocol version
secret.setIssuerId("merchant123");
secret.setIssuerPubKey(issuerKeyPair.getPublicKey().toString());
secret.setCurrency("USD");
secret.setFaceValue(1000000L);  // $10.00 in cents
secret.setIssuedAt(Instant.now().getEpochSecond());
secret.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS).getEpochSecond());
secret.setMerchantId("shop_xyz");  // Optional

// 3. Sign the voucher (excluding issuerSig tag)
byte[] canonicalBytes = secret.getCanonicalBytesWithoutSignature();
Signature signature = issuerKeyPair.sign(canonicalBytes);
secret.setIssuerSignature(signature.toString());

// 4. Blind the secret for minting
// The entire JSON (kind, nonce, data, tags) gets hashed to curve
HashToCurveSecret htcs = new HashToCurveSecret(secret);
PublicKey Y = htcs.getPublicKey();
BigInteger r = generateBlindingFactor();  // Random scalar
PublicKey B_ = blind(Y, r, mintPubKey);  // B' = r*G + Y

// 5. Create mint request
BlindedMessage blindedMsg = new BlindedMessage(
    amount,
    keysetId,
    B_
);
PostMintRequest<VoucherSecret> request = new PostMintRequest<>(
    quoteId,
    List.of(blindedMsg)
);

// 6. Send to mint
PostMintResponse response = cashuClient.mint(request);

// 7. Unblind signature
PublicKey C_ = response.getSignatures().get(0);
PublicKey C = unblind(C_, r, mintPubKey);  // C = C' - r*K

// 8. Store proof with voucher secret
Proof<VoucherSecret> proof = new Proof<>(
    amount,
    keysetId,
    secret,  // Full VoucherSecret with all metadata
    C
);
wallet.store(proof);
```

**Spending flow:**
```java
// 1. Load voucher proof from storage
Proof<VoucherSecret> voucherProof = wallet.getProof(proofId);

// 2. Display voucher details to user
VoucherSecret secret = voucherProof.getSecret();
byte[] voucherIdBytes = secret.getData();  // Get voucher ID from data field
String voucherId = bytesToUuid(voucherIdBytes).toString();

System.out.println("Voucher ID: " + voucherId);
System.out.println("Issuer: " + secret.getIssuerId());
System.out.println("Value: $" + (secret.getFaceValue() / 100.0) + " " + secret.getCurrency());
System.out.println("Issued: " + Instant.ofEpochSecond(secret.getIssuedAt()));
System.out.println("Expires: " + Instant.ofEpochSecond(secret.getExpiresAt()));
System.out.println("Single-use: Yes (enforced by Cashu protocol)");
if (secret.getMerchantId() != null) {
    System.out.println("Valid at: " + secret.getMerchantId());
}

// 3. Validate expiry before spending
long now = Instant.now().getEpochSecond();
if (secret.getExpiresAt() < now) {
    throw new VoucherExpiredException("Voucher expired at " + secret.getExpiresAt());
}

// 4. Verify issuer signature (client-side validation)
String issuerPubKey = secret.getIssuerPubKey();
String issuerSig = secret.getIssuerSignature();
byte[] canonicalBytes = secret.getCanonicalBytesWithoutSignature();
if (!PublicKey.fromString(issuerPubKey).verify(canonicalBytes, Signature.fromString(issuerSig))) {
    throw new InvalidSignatureException("Issuer signature verification failed");
}

// 5. Create swap/melt request
PostSwapRequest<VoucherSecret> swapRequest = new PostSwapRequest<>(
    List.of(voucherProof),  // Inputs include the voucher proof
    newBlindedOutputs       // Outputs (can be RandomStringSecret or VoucherSecret)
);

// 6. Send to mint
PostSwapResponse response = cashuClient.swap(swapRequest);

// 7. Unblind and store new proofs
List<Proof<Secret>> newProofs = unblindSwapResponse(response, blindingFactors);
wallet.store(newProofs);
```

**Helper methods:**
```java
private byte[] uuidToBytes(UUID uuid) {
    ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
    bb.putLong(uuid.getMostSignificantBits());
    bb.putLong(uuid.getLeastSignificantBits());
    return bb.array();
}

private UUID bytesToUuid(byte[] bytes) {
    ByteBuffer bb = ByteBuffer.wrap(bytes);
    return new UUID(bb.getLong(), bb.getLong());
}
```

**Note:** The `getCanonicalBytesWithoutSignature()` method is provided by the `VoucherSecret` class itself (see class definition above).

### Cross-mint compatibility

To enable vouchers across multiple mints:

**Issuer-centric model:**
- Vouchers are issued by merchants/issuers, not mints.
- Multiple mints can accept the same voucher if they trust the issuer.
- Issuers maintain a public registry of participating mints.

**Federation protocol:**
- Mints form federations and share issuer allowlists.
- A voucher minted at Mint A can be melted at Mint B if both are in the same federation.
- Mints settle via Lightning Network or inter-mint swaps.

**Standards compliance:**
- All mints in a federation must support the same schema version(s).
- Validation rules should be consistent (or documented differences).
- Use a common JSON serialization library (Jackson) with deterministic tag ordering to prevent serialization bugs.

### Testing and compliance

**Test vectors:**
Provide test vouchers with known JSON representations, signatures, and expected validation outcomes:

```json
{
  "testVectors": [
    {
      "name": "valid-voucher-v1",
      "description": "Valid voucher with all required fields",
      "voucherJson": {
        "kind": "VOUCHER",
        "nonce": "a1b2c3d4e5f6g7h8",
        "data": "f69a1f2c8d3e4b5a9c7f1e2d3c4b5a6f",
        "tags": [
          {"key": "schemaVersion", "values": [1]},
          {"key": "issuerId", "values": ["merchant123"]},
          {"key": "issuerPubKey", "values": ["02a1b2c3d4e5f6789abc..."]},
          {"key": "currency", "values": ["USD"]},
          {"key": "faceValue", "values": [1000000]},
          {"key": "issuedAt", "values": [1705276800]},
          {"key": "expiresAt", "values": [1735689600]},
          {"key": "issuerSig", "values": ["3045022100..."]}
        ]
      },
      "canonicalJsonBytes": "7b226b696e64223a22564f5543484552222c...",
      "expectedHashToCurve": "02a1b2c3d4e5f6789...",
      "shouldPass": true
    },
    {
      "name": "expired-voucher",
      "description": "Voucher with expiry in the past",
      "voucherJson": {
        "kind": "VOUCHER",
        "tags": [
          {"key": "expiresAt", "values": [1000000000]}
        ]
      },
      "shouldPass": false,
      "expectedError": "VOUCHER_EXPIRED"
    },
    {
      "name": "invalid-signature",
      "description": "Voucher with incorrect issuer signature",
      "voucherJson": {
        "kind": "VOUCHER",
        "tags": [
          {"key": "issuerSig", "values": ["deadbeef..."]}
        ]
      },
      "shouldPass": false,
      "expectedError": "INVALID_ISSUER_SIGNATURE"
    }
  ]
}
```

**Test vector fields:**
- `voucherJson` - Complete JSON representation of the VoucherSecret
- `canonicalJsonBytes` - Hex-encoded UTF-8 bytes of the canonical JSON (sorted tags) for hash-to-curve input
- `expectedHashToCurve` - Expected result of hashing the canonical JSON bytes to curve point
- `shouldPass` - Whether this voucher should pass validation
- `expectedError` - Expected error code if shouldPass is false

**Compliance checklist:**
- [ ] JSON encoding is deterministic via sorted tags (same input → same JSON bytes)
- [ ] Issuer signature verification uses canonical JSON encoding (tags sorted by key name)
- [ ] Hash-to-curve receives JSON bytes from `toBytes()` method (inherited from WellKnownSecret)
- [ ] Expiry enforcement includes clock skew tolerance
- [ ] Error codes match the specification
- [ ] VoucherSecret extends WellKnownSecret properly with all required tag getters/setters
- [ ] schemaVersion tag is set to 1 and validated
- [ ] Single-use enforcement via spent proof database (standard Cashu behavior)
- [ ] Issuer allowlist is enforced (if configured)
- [ ] Currency validation matches mint capabilities
- [ ] Nostr identity key used for voucher signing (recommended)
