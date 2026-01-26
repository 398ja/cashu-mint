# Security Implementation Plan

**Scope:** Full-stack security remediation for Imani wallet system
**Status:** Draft - Pending Approval
**Created:** January 26, 2026
**Source Documents:**
- `docs/cashu-validation-rules.md` - Token validation audit
- `docs/wallet-security-dossier.md` - Comprehensive security analysis

---

## Executive Summary

This document provides a detailed implementation plan to address critical security vulnerabilities identified across the Imani wallet stack. The plan covers:

1. **Frontend (imani-apps):** Token fingerprinting, global redemption mutex, idempotency
2. **Backend (cashu-client):** Database constraints, transaction isolation, idempotency middleware
3. **Core Library (cashu-lib):** Proof fingerprinting utilities, validation helpers

All implementations follow Clean Code and SOLID principles as specified in `cashu-lib/AGENTS.md`.

---

## Technical Review Findings & Resolutions

This section addresses technical concerns raised during plan review.

### Finding 1: ESM Import Incompatibility

**Issue:** The plan assumes ESM imports (`import { computeTokenFingerprint }...`) but `shared/*.js` files are loaded as global scripts via `<script>` tags, not `<script type="module">`.

**Resolution:** Export all utilities on `window` object to maintain compatibility with current loading pattern. No bundler or module conversion required.

```javascript
// CORRECT: Global script pattern (used throughout this plan)
window.CryptoUtils = { sha256, bufferToHex, computeTokenFingerprint };

// NOT: ESM pattern (would break current loading)
// export { sha256, bufferToHex, computeTokenFingerprint };
```

**Decision:** Preserve current global script loading. All code samples in this document use `window` exports.

---

### Finding 2: Base64URL Encoding in cashuA/B Tokens

**Issue:** `atob()` fails on base64url-encoded tokens (containing `-` and `_` instead of `+` and `/`). This causes the canonical proof-based fingerprint to fail silently, falling back to whole-token hashing.

**Resolution:** Add base64url normalization before decoding:

```javascript
/**
 * Decodes base64url string to regular string.
 * Handles URL-safe encoding (-/_ instead of +/) and missing padding.
 */
function base64UrlDecode(base64url) {
    // Replace URL-safe characters with standard base64
    let base64 = base64url
        .replace(/-/g, '+')
        .replace(/_/g, '/');

    // Add padding if needed
    const padding = base64.length % 4;
    if (padding) {
        base64 += '='.repeat(4 - padding);
    }

    return atob(base64);
}
```

**Decision:** All token decoding in Section 3.1.1 updated to use `base64UrlDecode()`.

---

### Finding 3: HTTP Request Body Stream Exhaustion

**Issue:** `IdempotencyFilter` and `Nip98AuthenticationFilter` call `request.getInputStream().readAllBytes()` which exhausts the stream. Downstream controllers receive empty bodies.

**Resolution:** Wrap request with `ContentCachingRequestWrapper` before reading:

```java
@Override
protected void doFilterInternal(HttpServletRequest request, ...) {
    // Wrap request to enable multiple reads
    ContentCachingRequestWrapper wrappedRequest =
        new ContentCachingRequestWrapper(request);

    // Now safe to read body multiple times
    byte[] body = wrappedRequest.getContentAsByteArray();

    // Pass wrapped request to chain
    chain.doFilter(wrappedRequest, response);
}
```

**Decision:** All backend filter code samples updated to use `ContentCachingRequestWrapper`.

---

### Finding 4: Global SERIALIZABLE Isolation Performance Impact

**Issue:** Setting `TRANSACTION_SERIALIZABLE` at HikariCP level affects ALL queries, including read-only operations, severely impacting throughput.

**Resolution:** Use per-transaction isolation via `@Transactional` annotation only on write paths that require it:

```java
// CORRECT: Per-transaction isolation for specific write operations
@Transactional(isolation = Isolation.SERIALIZABLE)
public ReceiveResult receiveProofs(List<Proof> proofs) { ... }

// NOT: Global HikariCP setting
// config.setTransactionIsolation("TRANSACTION_SERIALIZABLE");
```

**Write paths requiring SERIALIZABLE + retry:**
1. `ProofService.receiveProofs()` - Token receipt
2. `ProofService.markProofsSpent()` - Token spending
3. `TokenDmService.claimDm()` - DM claiming

**Read paths (default READ_COMMITTED):**
- Balance queries
- Transaction history
- Proof listings

**Decision:** Section 4.2 updated to use per-transaction isolation with `@Retryable` on specific write methods.

---

## Table of Contents

1. [Traceability Matrix](#1-traceability-matrix)
2. [Priority Matrix](#2-priority-matrix)
3. [Phase 1: Hotfix (P0)](#3-phase-1-hotfix-p0)
4. [Phase 2: Idempotency & Hardening (P1)](#4-phase-2-idempotency--hardening-p1)
5. [Phase 3: Storage Security (P2)](#5-phase-3-storage-security-p2)
6. [Phase 4: Advanced Security (P3)](#6-phase-4-advanced-security-p3)
7. [Testing Strategy](#7-testing-strategy)
8. [Project Task Tracking](#8-project-task-tracking)
   - [8.1 Phase 0: Foundation](#81-phase-0-foundation-cashu-lib)
   - [8.2 Phase 1: Hotfix (P0)](#82-phase-1-hotfix-p0---critical)
   - [8.3 Phase 2: Idempotency (P1)](#83-phase-2-idempotency--hardening-p1)
   - [8.4 Phase 3: Storage Security (P2)](#84-phase-3-storage-security-p2)
   - [8.5 Phase 4: Advanced (P3)](#85-phase-4-advanced-security-p3)
   - [8.6 Deployment & Validation](#86-deployment--validation)
   - [8.7 Summary Statistics](#87-summary-statistics)

**Appendices:**
- [Appendix A: File Change Summary](#appendix-a-file-change-summary)
- [Appendix B: Reference Architecture](#appendix-b-reference-architecture)
- [Appendix C: Cross-Project Security Changes](#appendix-c-cross-project-security-changes)

---

## 1. Traceability Matrix

This section explicitly maps how each implementation item addresses concerns identified in the source security documents.

### 1.1 Mapping to `cashu-validation-rules.md`

The validation rules document identified **6 key safeguards** with their current status. Here is how this plan addresses each:

| Safeguard (from doc) | Status in Doc | Implementation Item | Resolution |
|---------------------|---------------|---------------------|------------|
| **Event-level dedup** (`processedEventIds`) | ✅ Implemented | Retained | Keep as defense-in-depth layer |
| **Processing lock** (`processingEventIds`) | ⚠️ Partial | **S-002** Global Redemption Mutex | Centralize lock in `tokenRedemption.js` so ALL entry points (DM, scan, paste) share the same lock |
| **Token fingerprint** (`substring(0,50)`) | ❌ CRITICAL | **S-001** Cryptographic Fingerprinting | Replace with SHA-256 hash of sorted proof secrets via `cryptoUtils.js` |
| **Duplicate check** (API response) | ✅ Implemented | Retained | Keep as server-side backup detection |
| **Idempotency Key** | ❌ Not Implemented | **S-004** Idempotency Keys | Add `X-Idempotency-Key` header derived from token fingerprint |
| **Cross-flow Mutex** | ❌ Not Implemented | **S-002** Global Redemption Mutex | Single `RedemptionLocks` Map shared across all redemption flows |

#### Addressing Tier 1 Critical Failure Modes

| Failure Mode (Section 2) | Root Cause | Implementation Fix |
|--------------------------|------------|-------------------|
| **2.1 Race Condition: Auto vs. Manual** | `processingEventIds` private to `DmPollService` | **S-002**: Centralize lock in `@imani/token-security` package. All flows call `TokenRedemption.redeem()` (exposed via integration) which acquires lock BEFORE any network call. |
| **2.2 Mint Collision Bug** | `substring(0,50)` captures only mint URL | **S-001**: `computeTokenFingerprint()` hashes sorted proof secrets, making each token cryptographically distinct regardless of mint URL. |
| **2.3 Double-Click & Retry** | No idempotency mechanism | **S-004 + S-007**: Deterministic idempotency key + `submit-button` component disables during processing. Server caches responses for 24 hours. |

#### Implementing Required Rules

| Rule (Section 3) | Implementation |
|------------------|----------------|
| **Rule #1: Cryptographic Token Fingerprint (P0)** | `@imani/token-security` package with `TokenFingerprinter` class using SHA-256. Primary method hashes sorted proof secrets; fallback hashes entire token. Exposed via `tokenSecurityIntegration.js`. |
| **Rule #2: Global Redemption Mutex (P0)** | `@imani/token-security` package with `RedemptionLock` class. Lock acquired by fingerprint BEFORE API call, released in `finally` block. 60-second timeout prevents stuck locks. Exposed as `window.TokenRedemption` via integration. |
| **Rule #3: Shared Crypto Utility** | All cryptographic operations centralized in `@imani/token-security` package with `CryptoAdapter` interface. Browser implementation uses Web Crypto API. Exposed as `window.CryptoUtils` via integration. |

---

### 1.2 Mapping to `wallet-security-dossier.md`

The security dossier is a comprehensive threat analysis. Here is how each major threat category is addressed:

#### Section 1: Top Critical Threats

| Threat (1.1 Financial) | Severity | Implementation Items |
|------------------------|----------|---------------------|
| **Replay / Double-Spend** | CRITICAL | **S-001** (fingerprint), **S-002** (mutex), **S-003** (DB constraint), **S-004** (idempotency) |
| **Race Conditions** | CRITICAL | **S-002** (global mutex), **S-005** (SERIALIZABLE isolation) |
| **Token Integrity Drift** | HIGH | Backend validates `proof.amount` sum, ignores client-supplied metadata |
| **Mint Collision (NUT-13)** | HIGH | **S-001** uses full proof secrets, not keyset ID, for fingerprinting |

| Threat (1.2 Data/Key) | Severity | Implementation Items |
|-----------------------|----------|---------------------|
| **Storage Compromise** | CRITICAL | **S-008** IndexedDB migration with AES-GCM encryption |
| **XSS / Injection** | CRITICAL | **S-009** CSP headers, input sanitization |
| **Authorization Gaps** | HIGH | **S-006** NIP-98 payload hash validation |
| **Supply Chain Attacks** | HIGH | Dependency audit (P3), CSP `connect-src` whitelist |

#### Section 2: Cashu Protocol Security

| Protocol Concern | Document Section | Implementation |
|-----------------|------------------|----------------|
| **NUT-07 Token State** | 2.2 | **S-010**: `safeDeleteProof()` checks state is `SPENT` before deletion |
| **NUT-13 Keyset Collision** | 2.3 | **S-001**: Fingerprint uses proof secrets (not keyset ID), immune to collision |
| **Mint Database Isolation** | 2.4 | **S-005**: SERIALIZABLE transaction isolation with retry logic |

#### Section 3: Nostr Integration Security

| Concern | Document Section | Implementation |
|---------|------------------|----------------|
| **NIP-60 P2PK Key Exposure** | 3.1 | **S-012** (P3): Key wrapping pattern reduces identity key exposure |
| **NIP-17 No Forward Secrecy** | 3.2 | Acknowledged limitation; mitigated by secure key storage (**S-008**) |
| **NIP-17 Spam/Flooding** | 3.2 | Relay-side AUTH (existing); client-side `processedEventIds` LRU |
| **NIP-98 Validation** | 3.3 | **S-006**: Full validation including `payload` tag SHA-256 hash |

#### Section 4: Secure Storage Architecture

| Recommendation | Document Section | Implementation |
|----------------|------------------|----------------|
| **Never use localStorage for secrets** | 4.1 | **S-008**: `secureStorage.js` with IndexedDB + AES-GCM |
| **AES-GCM with unique IV** | 4.2 | **S-008**: 12-byte random IV per write, 128-bit tag |
| **Non-extractable keys** | 4.2 | **S-008**: `extractable: false` in `deriveKey()` |
| **PBKDF2 600k iterations** | 4.3 | **S-008**: OWASP 2023 recommendation implemented |
| **WebAuthn PRF** | 4.4 | **S-011** (P3): Research and prototype in `webauthnPrf.js` |

#### Section 5: XSS Mitigation

| Strategy | Document Section | Implementation |
|----------|------------------|----------------|
| **Content Security Policy** | 5.1 | **S-009**: Strict CSP via nginx headers |
| **Input Sanitization** | 5.2 | DOMPurify for DM content, QR payloads (recommended) |
| **Cookie Security** | 5.3 | `Secure; HttpOnly; SameSite=Strict` (backend) |

#### Section 6: Idempotency & Concurrency

| Control | Document Section | Implementation |
|---------|------------------|----------------|
| **Client-Side Idempotency** | 6.1 | **S-004**: `generateIdempotencyKey()` in `api.js` |
| **Server-Side Idempotency** | 6.2 | **S-004**: `IdempotencyFilter.java` with 24-hour TTL |
| **Global Redemption Mutex** | 6.3 | **S-002**: `RedemptionLocks` Map in `tokenRedemption.js` |
| **Strong Token Fingerprinting** | 6.4 | **S-001**: `computeTokenFingerprint()` with proof secrets |

#### Section 7: Test Matrix Coverage

| Test Scenario (from doc) | Implementation Coverage |
|--------------------------|------------------------|
| Double-click "Redeem" | **S-002** mutex + **S-007** button + **S-004** idempotency |
| Re-broadcast old DM | `processedEventIds` LRU (existing) + **S-002** fingerprint check |
| Auto + manual simultaneous | **S-002** global mutex serializes all flows |
| Two tokens from same mint | **S-001** fingerprint distinguishes by proof secrets |
| Token with edited amount | Backend validates `proof.amount` sum |
| XSS reading localStorage | **S-008** secrets in IndexedDB, encrypted |
| NIP-98 replay after 2 min | **S-006** validates timestamp within 60s |

#### Section 8: Implementation Checklist Mapping

| Checklist Item | Phase | Status |
|----------------|-------|--------|
| Fix fingerprinting (SHA-256) | P0 | **S-001** |
| Global mutex | P0 | **S-002** |
| Button disable | P1 | **S-007** |
| CSP audit | P2 | **S-009** |
| IndexedDB migration | P2 | **S-008** |
| Idempotency keys | P1 | **S-004** |
| NIP-98 hardening | P1 | **S-006** |
| NUT-07 integration | P2 | **S-010** |
| WebAuthn PRF | P3 | **S-011** |
| Key wrapping | P3 | **S-012** |

---

### 1.3 Defense-in-Depth Summary

The implementation creates **three layers of protection** against token duplication:

```
┌─────────────────────────────────────────────────────────────────────┐
│                     LAYER 1: UI PREVENTION                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ submit-btn  │  │ Fingerprint │  │ Global      │                 │
│  │ (disables)  │  │ (local dup) │  │ Mutex       │                 │
│  │ S-007       │  │ S-001       │  │ S-002       │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
│         ↓                ↓                ↓                         │
├─────────────────────────────────────────────────────────────────────┤
│                     LAYER 2: API PREVENTION                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ NIP-98      │  │ Idempotency │  │ Payload     │                 │
│  │ (auth)      │  │ (cache)     │  │ (integrity) │                 │
│  │ existing    │  │ S-004       │  │ S-006       │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
│         ↓                ↓                ↓                         │
├─────────────────────────────────────────────────────────────────────┤
│                     LAYER 3: DATABASE PREVENTION                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ UNIQUE      │  │ SERIALIZABLE│  │ ON CONFLICT │                 │
│  │ constraint  │  │ isolation   │  │ DO NOTHING  │                 │
│  │ S-003       │  │ S-005       │  │ S-003       │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
└─────────────────────────────────────────────────────────────────────┘

If ANY layer fails, the next layer catches the duplicate.
All three must fail for inflation to occur.
```

---

## 2. Priority Matrix

| ID | Issue | Severity | Component | Phase |
|----|-------|----------|-----------|-------|
| S-001 | Weak token fingerprinting (`substring(0,50)`) | **CRITICAL** | Frontend | P0 |
| S-002 | No global redemption mutex | **CRITICAL** | Frontend | P0 |
| S-003 | No unique constraint on proof commitments | **CRITICAL** | Backend DB | P0 |
| S-004 | No idempotency keys | **HIGH** | Full Stack | P1 |
| S-005 | No SERIALIZABLE transaction isolation | **HIGH** | Backend DB | P1 |
| S-006 | NIP-98 payload hash validation incomplete | **HIGH** | Backend | P1 |
| S-007 | Button state during submission | **MEDIUM** | Frontend | P1 |
| S-008 | Secrets in localStorage (XSS vulnerable) | **HIGH** | Frontend | P2 |
| S-009 | No CSP headers | **MEDIUM** | Frontend/Backend | P2 |
| S-010 | No NUT-07 state check before deletion | **MEDIUM** | Frontend | P2 |
| S-011 | WebAuthn PRF for hardware-backed keys | **LOW** | Frontend | P3 |
| S-012 | NIP-60 key wrapping pattern | **LOW** | Frontend | P3 |

---

## 3. Phase 1: Hotfix (P0)

**Timeline:** Immediate
**Risk Level:** Critical financial loss prevention

### 3.1 Architecture: New `@imani/token-security` Package

Following the existing pattern (e.g., `@imani/dm-poll`), we create a new TypeScript package that encapsulates all token security logic with proper adapters for browser integration.

**Package Structure:**

```
packages/token-security/
├── package.json
├── tsconfig.json
├── tsup.config.ts
├── src/
│   ├── index.ts                    # Main exports
│   ├── types/
│   │   ├── index.ts
│   │   └── config.ts               # Configuration types
│   ├── core/
│   │   ├── index.ts
│   │   ├── TokenFingerprinter.ts   # SHA-256 fingerprinting
│   │   ├── RedemptionLock.ts       # Global mutex implementation
│   │   └── TokenDecoder.ts         # Base64url + cashuA/B parsing
│   ├── adapters/
│   │   ├── index.ts
│   │   ├── StorageAdapter.ts       # Interface for persistence
│   │   ├── ApiAdapter.ts           # Interface for redemption API
│   │   └── CryptoAdapter.ts        # Interface for Web Crypto
│   ├── defaults/
│   │   ├── index.ts
│   │   ├── BrowserStorageAdapter.ts
│   │   ├── BrowserCryptoAdapter.ts
│   │   └── NoopStorageAdapter.ts
│   └── errors.ts
└── tests/
    ├── TokenFingerprinter.test.ts
    ├── RedemptionLock.test.ts
    └── TokenDecoder.test.ts
```

**Integration Pattern:**

```
┌─────────────────────────────────────────────────────────────────┐
│                         Browser Runtime                          │
├─────────────────────────────────────────────────────────────────┤
│  shared/tokenSecurityIntegration.js                             │
│  ├── Loads token-security.browser.min.js                        │
│  ├── Creates adapters from existing globals (api, storage)      │
│  └── Exposes TokenSecurity on window                            │
├─────────────────────────────────────────────────────────────────┤
│  lib/token-security.browser.min.js (built by tsup)              │
│  ├── TokenFingerprinter                                         │
│  ├── RedemptionLock                                             │
│  └── TokenDecoder                                               │
└─────────────────────────────────────────────────────────────────┘
```

---

### 3.2 S-001: Cryptographic Token Fingerprinting

**Problem:** `token.substring(0, 50)` uses mint URL as identifier, causing:
- False positives: Different tokens from same mint rejected as duplicates
- False negatives: Same token with different whitespace treated as different

**Solution:** TypeScript package with SHA-256 fingerprinting based on sorted proof secrets.

#### 3.2.1 Package: `packages/token-security/package.json`

**File:** `packages/token-security/package.json` (NEW)

```json
{
  "name": "@imani/token-security",
  "version": "0.1.0",
  "description": "Token fingerprinting, redemption mutex, and security utilities for Cashu tokens",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "import": { "types": "./dist/index.d.ts", "default": "./dist/index.js" },
      "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
    }
  },
  "files": ["dist", "README.md"],
  "scripts": {
    "build": "tsup",
    "dev": "tsup --watch",
    "test": "vitest",
    "test:run": "vitest run",
    "test:coverage": "vitest run --coverage",
    "typecheck": "tsc --noEmit",
    "clean": "rm -rf dist"
  },
  "devDependencies": {
    "@types/node": "^20.10.0",
    "typescript": "^5.3.3",
    "tsup": "^8.0.1",
    "vitest": "^2.0.0"
  },
  "keywords": ["imani", "cashu", "token", "fingerprint", "security", "redemption"],
  "license": "MIT"
}
```

#### 3.2.2 Core: `TokenDecoder.ts`

**File:** `packages/token-security/src/core/TokenDecoder.ts` (NEW)

```typescript
/**
 * Token decoder with base64url support for cashuA/cashuB tokens.
 *
 * @module TokenDecoder
 */

export interface DecodedToken {
  token: Array<{
    mint: string;
    proofs: Array<{
      secret: string;
      C: string;
      amount: number;
      id: string;
    }>;
  }>;
  unit?: string;
  memo?: string;
}

/**
 * Decodes base64url string to regular string.
 * Handles URL-safe encoding (-/_ instead of +/) and missing padding.
 *
 * cashuA/B tokens use base64url which differs from standard base64:
 * - Uses '-' instead of '+'
 * - Uses '_' instead of '/'
 * - May omit padding '='
 */
export function base64UrlDecode(base64url: string): string {
  // Replace URL-safe characters with standard base64
  let base64 = base64url
    .replace(/-/g, '+')
    .replace(/_/g, '/');

  // Add padding if needed
  const padding = base64.length % 4;
  if (padding) {
    base64 += '='.repeat(4 - padding);
  }

  // Use Buffer in Node.js, atob in browser
  if (typeof Buffer !== 'undefined') {
    return Buffer.from(base64, 'base64').toString('utf-8');
  }
  return atob(base64);
}

/**
 * Decodes cashuA/cashuB token to JSON structure.
 * Handles both standard base64 and base64url encoding.
 *
 * @param token - cashuA/cashuB encoded token
 * @returns Decoded token or null if invalid
 */
export function decodeToken(token: string): DecodedToken | null {
  try {
    let payload = token.trim();

    // Strip cashu: URI prefix
    if (payload.startsWith('cashu:')) {
      payload = payload.slice(6);
    }

    // Strip cashuA/cashuB prefix
    if (payload.startsWith('cashuA')) {
      payload = payload.slice(6);
    } else if (payload.startsWith('cashuB')) {
      payload = payload.slice(6);
    }

    // Decode using base64url (handles both standard and URL-safe)
    const json = base64UrlDecode(payload);
    return JSON.parse(json) as DecodedToken;
  } catch {
    return null;
  }
}
```

#### 3.2.3 Core: `TokenFingerprinter.ts`

**File:** `packages/token-security/src/core/TokenFingerprinter.ts` (NEW)

```typescript
/**
 * Cryptographic fingerprinting for Cashu tokens.
 *
 * @module TokenFingerprinter
 */

import { decodeToken, type DecodedToken } from './TokenDecoder';
import type { CryptoAdapter } from '../adapters/CryptoAdapter';

export interface TokenFingerprinterConfig {
  crypto: CryptoAdapter;
}

/**
 * Computes collision-resistant fingerprints for Cashu tokens.
 *
 * Primary method: SHA-256 of sorted proof secrets + mint URL
 * Fallback method: SHA-256 of entire trimmed token string
 *
 * The proof-based method is preferred because:
 * 1. Canonical - same proofs always produce same fingerprint
 * 2. Collision-resistant - different tokens never collide
 * 3. Whitespace-insensitive - parsing normalizes input
 */
export class TokenFingerprinter {
  private crypto: CryptoAdapter;

  constructor(config: TokenFingerprinterConfig) {
    this.crypto = config.crypto;
  }

  /**
   * Computes strong fingerprint for Cashu token.
   *
   * @param token - cashuA or cashuB encoded token
   * @returns 64-character hex fingerprint
   */
  async computeFingerprint(token: string): Promise<string> {
    const trimmed = token.trim();

    try {
      // Attempt proof-based fingerprint (canonical)
      const decoded = decodeToken(trimmed);
      if (decoded?.token?.length) {
        const secrets = decoded.token
          .flatMap(t => t.proofs || [])
          .map(p => p.secret)
          .filter(Boolean)
          .sort();

        if (secrets.length > 0) {
          const mintUrl = decoded.token[0]?.mint || '';
          const input = secrets.join('|') + '||' + mintUrl;
          return await this.crypto.sha256(input);
        }
      }
    } catch (e) {
      console.warn('[TokenFingerprinter] Proof-based fingerprint failed, using fallback:', e);
    }

    // Fallback: hash entire token
    return await this.crypto.sha256(trimmed);
  }

  /**
   * Extracts proof secrets from token for debugging.
   */
  getProofSecrets(token: string): string[] {
    const decoded = decodeToken(token.trim());
    if (!decoded?.token) return [];

    return decoded.token
      .flatMap(t => t.proofs || [])
      .map(p => p.secret)
      .filter(Boolean);
  }
}
```

#### 3.2.4 Adapter: `CryptoAdapter.ts`

**File:** `packages/token-security/src/adapters/CryptoAdapter.ts` (NEW)

```typescript
/**
 * Adapter interface for cryptographic operations.
 * Allows different implementations for browser vs Node.js.
 */
export interface CryptoAdapter {
  /**
   * Computes SHA-256 hash of input string.
   * @returns Hex-encoded hash (64 characters)
   */
  sha256(input: string): Promise<string>;

  /**
   * Generates random bytes.
   * @param length Number of bytes
   */
  randomBytes(length: number): Uint8Array;
}
```

#### 3.2.5 Default: `BrowserCryptoAdapter.ts`

**File:** `packages/token-security/src/defaults/BrowserCryptoAdapter.ts` (NEW)

```typescript
/**
 * Browser implementation of CryptoAdapter using Web Crypto API.
 */
import type { CryptoAdapter } from '../adapters/CryptoAdapter';

export class BrowserCryptoAdapter implements CryptoAdapter {
  async sha256(input: string): Promise<string> {
    const encoder = new TextEncoder();
    const data = encoder.encode(input);
    const hashBuffer = await crypto.subtle.digest('SHA-256', data);
    return this.bufferToHex(hashBuffer);
  }

  randomBytes(length: number): Uint8Array {
    return crypto.getRandomValues(new Uint8Array(length));
  }

  private bufferToHex(buffer: ArrayBuffer): string {
    return Array.from(new Uint8Array(buffer))
      .map(b => b.toString(16).padStart(2, '0'))
      .join('');
  }
}
```

---

### 3.3 S-002: Global Redemption Mutex

**Problem:** Race condition between auto-redeem (DmPollService) and manual-redeem (scan, paste) allows duplicate API calls.

**Solution:** TypeScript `RedemptionLock` class with timeout and source tracking.

#### 3.3.1 Core: `RedemptionLock.ts`

**File:** `packages/token-security/src/core/RedemptionLock.ts` (NEW)

```typescript
/**
 * Global redemption lock manager.
 * Prevents concurrent redemption attempts for the same token.
 *
 * @module RedemptionLock
 */

export interface LockEntry {
  timestamp: number;
  source: string;
}

export interface RedemptionLockConfig {
  /** Lock timeout in milliseconds (default: 60000) */
  timeoutMs?: number;
}

export class RedemptionLock {
  private locks = new Map<string, LockEntry>();
  private timeoutMs: number;

  constructor(config: RedemptionLockConfig = {}) {
    this.timeoutMs = config.timeoutMs ?? 60000;
  }

  /**
   * Attempts to acquire lock for a token fingerprint.
   *
   * @param fingerprint - Token fingerprint
   * @param source - Caller identifier (e.g., 'dm-auto', 'manual-paste')
   * @returns True if lock acquired, false if already locked
   */
  acquire(fingerprint: string, source: string): boolean {
    const existing = this.locks.get(fingerprint);

    if (existing) {
      const elapsed = Date.now() - existing.timestamp;
      if (elapsed < this.timeoutMs) {
        console.log(`[RedemptionLock] Lock held by ${existing.source}, rejecting ${source}`);
        return false;
      }
      console.warn(`[RedemptionLock] Releasing stale lock from ${existing.source}`);
    }

    this.locks.set(fingerprint, { timestamp: Date.now(), source });
    return true;
  }

  /**
   * Releases lock for a token fingerprint.
   */
  release(fingerprint: string): void {
    this.locks.delete(fingerprint);
  }

  /**
   * Checks if fingerprint is currently locked.
   */
  isLocked(fingerprint: string): boolean {
    const entry = this.locks.get(fingerprint);
    if (!entry) return false;

    const elapsed = Date.now() - entry.timestamp;
    return elapsed < this.timeoutMs;
  }

  /**
   * Gets current lock count (for debugging).
   */
  getLockCount(): number {
    return this.locks.size;
  }

  /**
   * Clears all locks (for testing).
   */
  clearAll(): void {
    this.locks.clear();
  }
}

// Singleton instance for global access
let globalLock: RedemptionLock | null = null;

export function getGlobalRedemptionLock(): RedemptionLock {
  if (!globalLock) {
    globalLock = new RedemptionLock();
  }
  return globalLock;
}
```

#### 3.3.2 Main Export: `index.ts`

**File:** `packages/token-security/src/index.ts` (NEW)

```typescript
/**
 * @imani/token-security
 *
 * Token fingerprinting, redemption mutex, and security utilities.
 */

// Core exports
export { TokenFingerprinter, type TokenFingerprinterConfig } from './core/TokenFingerprinter';
export { RedemptionLock, getGlobalRedemptionLock, type LockEntry, type RedemptionLockConfig } from './core/RedemptionLock';
export { decodeToken, base64UrlDecode, type DecodedToken } from './core/TokenDecoder';

// Adapter interfaces
export type { CryptoAdapter } from './adapters/CryptoAdapter';
export type { StorageAdapter } from './adapters/StorageAdapter';
export type { ApiAdapter, RedemptionResult } from './adapters/ApiAdapter';

// Default implementations
export { BrowserCryptoAdapter } from './defaults/BrowserCryptoAdapter';
export { BrowserStorageAdapter } from './defaults/BrowserStorageAdapter';

// Errors
export { TokenSecurityError, LockAcquisitionError, DuplicateTokenError } from './errors';

// Factory function for easy setup
export { createTokenSecurityService, type TokenSecurityService } from './createService';
```

---

### 3.4 Integration: `tokenSecurityIntegration.js`

**File:** `imani-apps/shared/tokenSecurityIntegration.js` (NEW)

This integration file bridges the TypeScript package to the vanilla JS frontend.

```javascript
/**
 * Token Security Integration Module
 *
 * Bridges the Imani wallet with @imani/token-security package.
 * Provides seamless integration for token fingerprinting and redemption locking.
 *
 * Usage:
 * 1. Load token-security.browser.min.js before this script
 * 2. Call TokenSecurityIntegration.init() after page load
 * 3. Use window.TokenSecurity for fingerprinting and locking
 */

console.log('[tokenSecurityIntegration] Module loaded v1');

const TokenSecurityIntegration = {
  _initialized: false,
  _fingerprinter: null,
  _lock: null,

  /**
   * Initialize the token security integration
   */
  async init() {
    if (this._initialized) {
      console.log('[tokenSecurityIntegration] Already initialized');
      return;
    }

    if (typeof ImaniTokenSecurity === 'undefined') {
      console.error('[tokenSecurityIntegration] ImaniTokenSecurity not loaded');
      return null;
    }

    try {
      console.log('[tokenSecurityIntegration] Initializing...');

      // Create browser crypto adapter
      const cryptoAdapter = new ImaniTokenSecurity.BrowserCryptoAdapter();

      // Create fingerprinter
      this._fingerprinter = new ImaniTokenSecurity.TokenFingerprinter({
        crypto: cryptoAdapter
      });

      // Get global redemption lock
      this._lock = ImaniTokenSecurity.getGlobalRedemptionLock();

      this._initialized = true;
      console.log('[tokenSecurityIntegration] Initialized successfully');

      // Expose on window for global access
      this._exposeGlobals();

    } catch (error) {
      console.error('[tokenSecurityIntegration] Initialization failed:', error);
    }
  },

  /**
   * Expose functions on window for backwards compatibility
   */
  _exposeGlobals() {
    // Fingerprinting
    window.computeTokenFingerprint = (token) => {
      return this._fingerprinter.computeFingerprint(token);
    };

    // Lock management
    window.TokenRedemption = {
      acquireLock: (fingerprint, source) => this._lock.acquire(fingerprint, source),
      releaseLock: (fingerprint) => this._lock.release(fingerprint),
      isLocked: (fingerprint) => this._lock.isLocked(fingerprint),

      /**
       * Central redemption function with mutex protection
       */
      redeem: async (token, options = {}) => {
        const { source = 'unknown', skipDuplicateCheck = false } = options;

        // Step 1: Compute fingerprint
        const fingerprint = await this._fingerprinter.computeFingerprint(token);
        console.log(`[TokenRedemption] Attempting from ${source}, fingerprint: ${fingerprint.slice(0, 8)}...`);

        // Step 2: Check for local duplicate
        if (!skipDuplicateCheck && typeof window.hasTokenBeenReceived === 'function') {
          const isDuplicate = await window.hasTokenBeenReceived(token);
          if (isDuplicate) {
            throw new Error('TOKEN_ALREADY_RECEIVED: Token has already been received');
          }
        }

        // Step 3: Acquire global lock
        if (!this._lock.acquire(fingerprint, source)) {
          throw new Error('TOKEN_LOCKED: Token is currently being processed');
        }

        try {
          // Step 4: Call API
          const result = await window.api.receive(token);

          // Step 5: Check API response for server-side duplicate
          if (result.duplicate_count > 0 && result.importedCount === 0) {
            throw new Error('TOKEN_DUPLICATE_SERVER: Token was already redeemed on server');
          }

          // Step 6: Mark as received locally
          if (typeof window.markTokenReceived === 'function') {
            await window.markTokenReceived(token);
          }

          console.log(`[TokenRedemption] Success from ${source}: imported=${result.importedCount}`);
          return result;

        } finally {
          // Step 7: Always release lock
          this._lock.release(fingerprint);
        }
      }
    };

    // Token decoding utilities
    window.CryptoUtils = {
      decodeToken: ImaniTokenSecurity.decodeToken,
      base64UrlDecode: ImaniTokenSecurity.base64UrlDecode,
      computeTokenFingerprint: window.computeTokenFingerprint
    };
  },

  /**
   * Get fingerprinter instance
   */
  getFingerprinter() {
    return this._fingerprinter;
  },

  /**
   * Get lock instance
   */
  getLock() {
    return this._lock;
  }
};

// Expose globally
if (typeof window !== 'undefined') {
  window.TokenSecurityIntegration = TokenSecurityIntegration;
}
```

---

### 3.5 Update `storage.js` to Use Integration

**File:** `imani-apps/shared/storage.js` (MODIFY)

```javascript
// NOTE: Requires tokenSecurityIntegration.js to be initialized first
// Access via window.computeTokenFingerprint

const RECEIVED_TOKENS_KEY = 'imani_received_tokens';
const MAX_RECEIVED_TOKENS = 500; // LRU cache limit

/**
 * Checks if token has already been received.
 * Uses SHA-256 fingerprint for collision-resistant detection.
 *
 * @param {string} token - Cashu token to check
 * @returns {Promise<boolean>} True if token was previously received
 */
async function hasTokenBeenReceived(token) {
    const fingerprint = await window.computeTokenFingerprint(token);
    const received = getReceivedTokens();
    return received.includes(fingerprint);
}

/**
 * Marks token as received.
 * Maintains LRU cache of fingerprints.
 *
 * @param {string} token - Cashu token to mark as received
 */
async function markTokenReceived(token) {
    const fingerprint = await computeTokenFingerprint(token);
    const received = getReceivedTokens();

    // Prevent duplicates in list
    if (!received.includes(fingerprint)) {
        received.push(fingerprint);

        // LRU eviction
        while (received.length > MAX_RECEIVED_TOKENS) {
            received.shift();
        }

        localStorage.setItem(RECEIVED_TOKENS_KEY, JSON.stringify(received));
    }
}

/**
 * Gets list of received token fingerprints.
 * @returns {string[]} Array of fingerprints
 */
function getReceivedTokens() {
    try {
        return JSON.parse(localStorage.getItem(RECEIVED_TOKENS_KEY) || '[]');
    } catch {
        return [];
    }
}

// Export for global access (vanilla JS compatibility)
window.hasTokenBeenReceived = hasTokenBeenReceived;
window.markTokenReceived = markTokenReceived;
```

---

### 3.6 S-003: Database Unique Constraint on Proofs

**Problem:** No unique constraint on proof commitments allows duplicate storage.

**Affected Files:**
- `cashu-client/wallet-plugin/wallet-core/wallet-core-cashu/src/main/resources/db/migration/wallet/`

#### 3.6.1 Add Migration for Unique Constraint

**File:** `V3__add_proof_unique_constraint.sql` (NEW)

```sql
-- Migration: Add unique constraint on proof commitment (C)
-- Prevents duplicate proof storage at database level
--
-- The (mint_url, C) combination uniquely identifies a proof:
-- - Same proof at different mints is technically possible (rare)
-- - Same C value should never appear twice for the same mint

-- Add unique constraint
ALTER TABLE proofs
ADD CONSTRAINT uk_proofs_mint_commitment
UNIQUE (mint_url, C);

-- Add index for spent proof lookups (performance)
CREATE INDEX IF NOT EXISTS idx_proofs_commitment
ON proofs(C);

-- Add comment documenting constraint purpose
COMMENT ON CONSTRAINT uk_proofs_mint_commitment ON proofs IS
'Prevents duplicate proof storage. Cashu proofs are uniquely identified by their commitment (C) within a mint.';
```

#### 3.6.2 Update ProofRepository for Conflict Handling

**File:** `cashu-client/wallet-plugin/wallet-core/wallet-core-cashu/src/main/java/xyz/tcheeric/wallet/core/db/ProofRepository.java`

```java
/**
 * Stores proof with duplicate detection.
 *
 * @param proof Proof to store
 * @return true if stored successfully, false if duplicate
 * @throws DataAccessException for other database errors
 */
public boolean storeProofIfNotExists(Proof proof) {
    String sql = """
        INSERT INTO proofs (mint_url, unit, amount, C, secret_enc, dleq, keyset_id, p2pk_lock, spent)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (mint_url, C) DO NOTHING
        """;

    try {
        int rowsAffected = jdbcTemplate.update(sql,
            proof.getMintUrl(),
            proof.getUnit(),
            proof.getAmount(),
            proof.getC(),
            proof.getEncryptedSecret(),
            proof.getDleq(),
            proof.getKeysetId(),
            proof.getP2pkLock(),
            false
        );

        if (rowsAffected == 0) {
            log.info("Duplicate proof detected, skipping storage: C={}",
                proof.getC().substring(0, 16) + "...");
            return false;
        }

        return true;

    } catch (DuplicateKeyException e) {
        // Race condition: constraint violation
        log.debug("Concurrent duplicate proof insertion: {}", e.getMessage());
        return false;
    }
}
```

**Design Rationale (per AGENTS.md):**
- **Database as Last Line of Defense:** Even if application logic fails, DB prevents duplicates
- **ON CONFLICT DO NOTHING:** PostgreSQL/H2 compatible upsert semantics
- **Idempotent Operation:** Safe to retry without side effects

---

## 4. Phase 2: Idempotency & Hardening (P1)

**Timeline:** Sprint 1-2
**Risk Level:** High - Prevents network-related duplications

### 4.1 S-004: Idempotency Key Implementation

**Problem:** Network retries and double-clicks can cause duplicate operations.

#### 4.1.1 Frontend: Generate Idempotency Key

**File:** `imani-apps/shared/api.js`

```javascript
// NOTE: Requires cryptoUtils.js to be loaded first
// Add these functions to the existing ImaniApi class/object

/**
 * Generates deterministic idempotency key for token operations.
 *
 * Key derivation: SHA-256(fingerprint || userPubkey || operation)
 *
 * @param {string} token - Cashu token
 * @param {string} operation - Operation name (e.g., 'receive', 'send')
 * @returns {Promise<string>} Idempotency key
 */
async function generateIdempotencyKey(token, operation) {
    const fingerprint = await window.computeTokenFingerprint(token);
    const userPubkey = await getPublicKeyHex(); // Existing function
    const input = `${fingerprint}|${userPubkey}|${operation}`;
    return await window.sha256(input);
}

// Update receive method in the ImaniApi object to include idempotency key
// (Add to existing api object definition)
async receive(token) {
    const idempotencyKey = await generateIdempotencyKey(token, 'receive');

    return this.request('POST', '/api/v1/wallet/receive', { token }, {
        headers: {
            'X-Idempotency-Key': idempotencyKey
        }
    });
}
```

#### 4.1.2 Backend: Idempotency Middleware

**File:** `cashu-client/gateway-plugin/gateway-rest/src/main/java/xyz/tcheeric/gateway/rest/middleware/IdempotencyFilter.java` (NEW)

```java
package xyz.tcheeric.gateway.rest.middleware;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Filter that enforces idempotency for POST/PUT/DELETE requests.
 *
 * Idempotency keys are stored for 24 hours to handle delayed retries.
 * If a duplicate request arrives, the cached response is returned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final IdempotencyStore idempotencyStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Only apply to mutating methods
        if (!isMutatingMethod(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);

        // If no key provided, proceed without idempotency
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.debug("No idempotency key for {} {}", request.getMethod(), request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        String userId = extractUserId(request);
        String cacheKey = buildCacheKey(userId, idempotencyKey);

        // Check for cached response
        Optional<CachedResponse> cached = idempotencyStore.get(cacheKey);
        if (cached.isPresent()) {
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            writeCachedResponse(response, cached.get());
            return;
        }

        // Wrap response to capture output
        ContentCachingResponseWrapper wrappedResponse =
            new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(request, wrappedResponse);

            // Cache successful response
            if (isSuccessful(wrappedResponse.getStatus())) {
                CachedResponse toCache = new CachedResponse(
                    wrappedResponse.getStatus(),
                    wrappedResponse.getContentAsByteArray(),
                    wrappedResponse.getContentType()
                );
                idempotencyStore.put(cacheKey, toCache, CACHE_TTL);
            }

        } finally {
            wrappedResponse.copyBodyToResponse();
        }
    }

    private boolean isMutatingMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
    }

    private boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private String extractUserId(HttpServletRequest request) {
        // Extract from NIP-98 auth or API key
        return (String) request.getAttribute("userId");
    }

    private String buildCacheKey(String userId, String idempotencyKey) {
        return String.format("idempotency:%s:%s", userId, idempotencyKey);
    }

    private void writeCachedResponse(HttpServletResponse response, CachedResponse cached)
            throws IOException {
        response.setStatus(cached.status());
        response.setContentType(cached.contentType());
        response.getOutputStream().write(cached.body());
    }
}

record CachedResponse(int status, byte[] body, String contentType) {}
```

**File:** `IdempotencyStore.java` (Interface)

```java
package xyz.tcheeric.gateway.rest.middleware;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage interface for idempotency responses.
 * Implementations may use Redis, in-memory cache, or database.
 */
public interface IdempotencyStore {
    Optional<CachedResponse> get(String key);
    void put(String key, CachedResponse response, Duration ttl);
    void remove(String key);
}
```

---

### 4.2 S-005: SERIALIZABLE Transaction Isolation

**Problem:** Default READ COMMITTED allows race conditions in concurrent proof operations.

#### 4.2.1 Per-Transaction Isolation (NOT Global)

**Important:** Do NOT set isolation globally on HikariCP - this impacts ALL queries including reads.
Instead, use `@Transactional(isolation = Isolation.SERIALIZABLE)` only on specific write paths.

**NO CHANGES to `WalletDatabaseConfig.java`** - Keep default READ_COMMITTED for general queries.

**Write paths requiring SERIALIZABLE:**
- `ProofService.receiveProofs()` - Token receipt
- `ProofService.markProofsSpent()` - Token spending
- `TokenDmService.claimDm()` - DM claiming

**Read paths (default READ_COMMITTED):**
- Balance queries
- Transaction history
- Proof listings

#### 4.2.2 Add Retry Logic for Serialization Failures

**File:** `cashu-client/wallet-plugin/wallet-core/wallet-core-cashu/src/main/java/xyz/tcheeric/wallet/core/service/ProofService.java`

```java
/**
 * Stores proofs with SERIALIZABLE isolation and retry logic.
 *
 * SERIALIZABLE isolation may cause serialization failures (deadlocks)
 * when concurrent transactions conflict. These are retried automatically.
 */
@Transactional(isolation = Isolation.SERIALIZABLE)
@Retryable(
    retryFor = {SerializationFailureException.class, CannotAcquireLockException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 100, multiplier = 2)
)
public ReceiveResult receiveProofs(List<Proof> proofs) {
    int imported = 0;
    int duplicates = 0;

    for (Proof proof : proofs) {
        boolean stored = proofRepository.storeProofIfNotExists(proof);
        if (stored) {
            imported++;
        } else {
            duplicates++;
        }
    }

    return new ReceiveResult(imported, duplicates);
}
```

---

### 4.3 S-006: NIP-98 Payload Hash Validation

**Problem:** Server may not validate request body hash in NIP-98.

**File:** `cashu-client/gateway-plugin/gateway-security/src/main/java/xyz/tcheeric/gateway/security/Nip98AuthenticationFilter.java`

**Critical:** Must wrap request with `ContentCachingRequestWrapper` before reading body to prevent stream exhaustion for downstream controllers.

```java
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * NIP-98 Authentication Filter with payload hash validation.
 *
 * IMPORTANT: This filter wraps the request to enable multiple body reads.
 * The wrapped request is passed to the filter chain so downstream
 * controllers can still read the body.
 */
@Override
protected void doFilterInternal(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {

    // Wrap request to enable multiple body reads
    ContentCachingRequestWrapper wrappedRequest =
        new ContentCachingRequestWrapper(request);

    try {
        // Validate NIP-98 auth (reads body for hash validation)
        NostrEvent authEvent = extractAuthEvent(wrappedRequest);
        validateNip98Event(authEvent, wrappedRequest);

        // Set authenticated user on request
        wrappedRequest.setAttribute("userId", authEvent.getPubkey());

        // Pass WRAPPED request to chain (critical!)
        chain.doFilter(wrappedRequest, response);

    } catch (AuthenticationException e) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write(e.getMessage());
    }
}

/**
 * Validates NIP-98 authentication token including payload hash.
 */
private void validateNip98Event(NostrEvent event, ContentCachingRequestWrapper request)
        throws AuthenticationException {

    // Existing validations...
    validateKind(event);
    validateTimestamp(event);
    validateUrl(event, request);
    validateMethod(event, request);

    // NEW: Validate payload hash for POST/PUT
    if (hasBody(request.getMethod())) {
        validatePayloadHash(event, request);
    }
}

private void validatePayloadHash(NostrEvent event, ContentCachingRequestWrapper request)
        throws AuthenticationException {

    String expectedHash = getTagValue(event, "payload");
    if (expectedHash == null) {
        throw new AuthenticationException("NIP-98 payload tag required for " + request.getMethod());
    }

    String actualHash = computeBodyHash(request);
    if (!MessageDigest.isEqual(expectedHash.getBytes(), actualHash.getBytes())) {
        throw new AuthenticationException("NIP-98 payload hash mismatch");
    }
}

/**
 * Computes SHA-256 hash of request body.
 * Uses ContentCachingRequestWrapper.getContentAsByteArray() which can be called multiple times.
 */
private String computeBodyHash(ContentCachingRequestWrapper request) {
    try {
        // First, ensure body is cached by reading it
        request.getInputStream().readAllBytes();

        // Now get cached content (can be called multiple times)
        byte[] body = request.getContentAsByteArray();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
        return Hex.encodeHexString(hash);
    } catch (Exception e) {
        throw new AuthenticationException("Failed to compute payload hash", e);
    }
}
```

---

### 4.4 S-007: Button State Management

**Problem:** Users can click buttons multiple times during processing.

**File:** `imani-apps/shared/components/submit-button.js` (NEW)

```javascript
/**
 * Custom submit button with built-in loading state.
 * Prevents double-clicks by disabling during async operations.
 *
 * @example
 * <submit-button id="redeem-btn">Redeem Token</submit-button>
 *
 * // In JavaScript:
 * redeemBtn.execute(async () => {
 *     await TokenRedemption.redeem(token);
 * });
 */
class SubmitButton extends HTMLElement {
    constructor() {
        super();
        this.isProcessing = false;
    }

    connectedCallback() {
        this.originalText = this.textContent;
        this.addEventListener('click', this.handleClick.bind(this));
    }

    async handleClick(e) {
        e.preventDefault();

        if (this.isProcessing) {
            return;
        }

        if (this.onExecute) {
            await this.execute(this.onExecute);
        }
    }

    /**
     * Executes async function with loading state.
     *
     * @param {Function} fn - Async function to execute
     * @returns {Promise<any>} Function result
     */
    async execute(fn) {
        if (this.isProcessing) {
            console.warn('[SubmitButton] Already processing, ignoring click');
            return;
        }

        this.isProcessing = true;
        this.disabled = true;
        this.textContent = this.getAttribute('loading-text') || 'Processing...';
        this.classList.add('loading');

        try {
            return await fn();
        } finally {
            this.isProcessing = false;
            this.disabled = false;
            this.textContent = this.originalText;
            this.classList.remove('loading');
        }
    }
}

customElements.define('submit-button', SubmitButton);
```

---

## 5. Phase 3: Storage Security (P2)

**Timeline:** Sprint 3-4
**Risk Level:** Medium - XSS mitigation

### 5.1 S-008: IndexedDB Migration for Secrets

**Problem:** `localStorage` is accessible to XSS attacks.

#### 5.1.1 Create Encrypted IndexedDB Store

**File:** `imani-apps/shared/secureStorage.js` (NEW)

```javascript
/**
 * Encrypted IndexedDB storage for sensitive data.
 *
 * Security features:
 * 1. AES-GCM encryption with unique IV per write
 * 2. Non-exportable encryption key
 * 3. Key derived from password via PBKDF2
 * 4. IndexedDB isolated from XSS (cannot be read via document.cookie tricks)
 */

const DB_NAME = 'imani_secure_store';
const DB_VERSION = 1;
const STORE_NAME = 'secrets';

let db = null;
let encryptionKey = null;

/**
 * Initializes secure storage with password.
 *
 * @param {string} password - User password for key derivation
 * @param {Uint8Array} salt - Salt for PBKDF2 (stored separately)
 * @returns {Promise<void>}
 */
export async function initSecureStorage(password, salt) {
    // Derive encryption key from password
    const keyMaterial = await crypto.subtle.importKey(
        'raw',
        new TextEncoder().encode(password),
        'PBKDF2',
        false,
        ['deriveKey']
    );

    encryptionKey = await crypto.subtle.deriveKey(
        {
            name: 'PBKDF2',
            salt: salt,
            iterations: 600000, // OWASP 2023 recommendation
            hash: 'SHA-256'
        },
        keyMaterial,
        { name: 'AES-GCM', length: 256 },
        false, // Non-extractable!
        ['encrypt', 'decrypt']
    );

    // Open IndexedDB
    db = await openDatabase();
}

/**
 * Stores encrypted value.
 *
 * @param {string} key - Storage key
 * @param {any} value - Value to store (will be JSON stringified)
 */
export async function setSecure(key, value) {
    if (!encryptionKey || !db) {
        throw new Error('Secure storage not initialized');
    }

    // Generate unique IV for this write
    const iv = crypto.getRandomValues(new Uint8Array(12));

    const plaintext = new TextEncoder().encode(JSON.stringify(value));
    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv, tagLength: 128 },
        encryptionKey,
        plaintext
    );

    // Store IV + ciphertext together
    const combined = new Uint8Array(iv.length + ciphertext.byteLength);
    combined.set(iv, 0);
    combined.set(new Uint8Array(ciphertext), iv.length);

    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    await store.put({ key, data: combined });
}

/**
 * Retrieves and decrypts value.
 *
 * @param {string} key - Storage key
 * @returns {Promise<any>} Decrypted value or null
 */
export async function getSecure(key) {
    if (!encryptionKey || !db) {
        throw new Error('Secure storage not initialized');
    }

    const tx = db.transaction(STORE_NAME, 'readonly');
    const store = tx.objectStore(STORE_NAME);
    const record = await store.get(key);

    if (!record) {
        return null;
    }

    // Extract IV and ciphertext
    const iv = record.data.slice(0, 12);
    const ciphertext = record.data.slice(12);

    const plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv, tagLength: 128 },
        encryptionKey,
        ciphertext
    );

    return JSON.parse(new TextDecoder().decode(plaintext));
}

function openDatabase() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);

        request.onerror = () => reject(request.error);
        request.onsuccess = () => resolve(request.result);

        request.onupgradeneeded = (event) => {
            const db = event.target.result;
            if (!db.objectStoreNames.contains(STORE_NAME)) {
                db.createObjectStore(STORE_NAME, { keyPath: 'key' });
            }
        };
    });
}
```

---

### 5.2 S-009: Content Security Policy

**File:** `imani-apps/nginx.conf` (or served via backend headers)

```nginx
add_header Content-Security-Policy "
    default-src 'self';
    script-src 'self' 'wasm-unsafe-eval';
    style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;
    font-src 'self' https://fonts.gstatic.com;
    connect-src 'self'
        https://account.imani.casa
        wss://relay.imani.casa
        wss://relay.398ja.xyz;
    img-src 'self' data: https:;
    frame-ancestors 'none';
    base-uri 'self';
    form-action 'self';
" always;

add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

---

### 5.3 S-010: NUT-07 State Check Before Deletion

**File:** `imani-apps/shared/storage.js`

```javascript
/**
 * Safely deletes a proof after verifying it's spent.
 * Uses NUT-07 checkstate to prevent deleting unspent proofs.
 *
 * @param {Object} proof - Proof to delete
 * @param {string} mintUrl - Mint URL for state check
 * @returns {Promise<boolean>} True if deleted, false if not spent
 */
async function safeDeleteProof(proof, mintUrl) {
    try {
        // Check state with mint
        const state = await api.checkProofState(mintUrl, [proof]);

        if (state.states[0] !== 'SPENT') {
            console.warn('[storage] Refusing to delete unspent proof:', proof.C.slice(0, 16));
            return false;
        }

        // Safe to delete
        return await deleteProof(proof);

    } catch (error) {
        // Network error - be conservative, don't delete
        console.error('[storage] State check failed, preserving proof:', error);
        return false;
    }
}
```

---

## 6. Phase 4: Advanced Security (P3)

**Timeline:** Quarter 2
**Risk Level:** Low - Enhancement

### 6.1 S-011: WebAuthn PRF Investigation

**Goal:** Hardware-backed key derivation for high-value wallets.

**Research Tasks:**
1. Evaluate browser support matrix (Chrome, Safari, Firefox)
2. Prototype PRF-based key derivation
3. Design fallback for unsupported browsers
4. Plan migration path for existing users

**Prototype:** `imani-apps/shared/webauthnPrf.js`

```javascript
/**
 * WebAuthn PRF extension for hardware-backed keys.
 *
 * This is a PROTOTYPE - production use requires extensive testing
 * across platforms and graceful degradation.
 */

/**
 * Checks if PRF extension is supported.
 * @returns {Promise<boolean>}
 */
export async function isPrfSupported() {
    if (!window.PublicKeyCredential) {
        return false;
    }

    try {
        // Check for PRF extension support
        const available = await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
        return available;
    } catch {
        return false;
    }
}

/**
 * Derives encryption key using WebAuthn PRF.
 *
 * @param {Uint8Array} credentialId - Stored credential ID
 * @param {string} purpose - Key purpose (e.g., 'wallet-encryption-v1')
 * @returns {Promise<CryptoKey>} Derived AES-GCM key
 */
export async function deriveKeyWithPrf(credentialId, purpose) {
    const salt = new TextEncoder().encode(purpose);

    const assertion = await navigator.credentials.get({
        publicKey: {
            challenge: crypto.getRandomValues(new Uint8Array(32)),
            allowCredentials: [{ id: credentialId, type: 'public-key' }],
            extensions: {
                prf: {
                    eval: { first: salt }
                }
            }
        }
    });

    const prfOutput = assertion.getClientExtensionResults().prf?.results?.first;
    if (!prfOutput) {
        throw new Error('PRF extension not available or failed');
    }

    // Import PRF output as AES key
    return await crypto.subtle.importKey(
        'raw',
        prfOutput,
        { name: 'AES-GCM', length: 256 },
        false,
        ['encrypt', 'decrypt']
    );
}
```

---

### 6.2 S-012: NIP-60 Key Wrapping

**Goal:** Reduce NIP-46 calls by wrapping data keys.

**Design:**
1. Generate random Data Key on first use
2. Encrypt Data Key with Identity Key (NIP-44)
3. Store wrapped Data Key in `kind:17375`
4. Use Data Key for all token encryption
5. Only call NIP-46 at session start to unwrap

---

## 7. Testing Strategy

### 7.1 Unit Tests

| Component | Test | Framework |
|-----------|------|-----------|
| `cryptoUtils.js` | Fingerprint determinism | Vitest |
| `tokenRedemption.js` | Lock acquisition/release | Vitest |
| `ProofRepository` | Duplicate detection | JUnit 5 |
| `IdempotencyFilter` | Response caching | JUnit 5 |

### 7.2 Integration Tests

| Scenario | Test |
|----------|------|
| Double-click redemption | Playwright E2E |
| Concurrent auto + manual | Playwright E2E |
| Network retry | Mock server with latency |
| NIP-98 replay | Send stale auth header |

### 7.3 Security Tests

| Category | Test |
|----------|------|
| XSS | Inject script via token memo |
| CSRF | Send request without auth |
| Replay | Re-broadcast old DM |
| Collision | Two tokens from same mint |

---

## 8. Project Task Tracking

This section provides detailed task tracking tables for each implementation phase across all projects.

**Status Legend:**
- `pending` - Not started
- `in_progress` - Currently being worked on
- `blocked` - Waiting on dependency
- `review` - Code complete, awaiting review
- `done` - Completed and merged

---

### 8.1 Phase 0: Foundation (cashu-lib)

Core library changes that all other projects depend on.

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P0-LIB-001 | Create `ProofFingerprint.java` utility class | cashu-lib | - | done | 145291a |
| P0-LIB-002 | Create `TokenFingerprint.java` wrapper | cashu-lib | P0-LIB-001 | done | 145291a |
| P0-LIB-003 | Add unit tests for fingerprinting | cashu-lib | P0-LIB-002 | done | 145291a |
| P0-LIB-004 | Update CHANGELOG and bump to 0.13.0 | cashu-lib | P0-LIB-003 | done | 145291a |
| P0-LIB-005 | Publish to Maven Central | cashu-lib | P0-LIB-004 | done | 145291a |

---

### 8.2 Phase 1: Hotfix (P0) - Critical

Immediate fixes to prevent token duplication/inflation.

#### 8.2.1 Frontend (imani-apps)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P0-FE-001 | Create `packages/token-security/` package structure | imani-apps | - | pending | |
| P0-FE-002 | Implement `TokenDecoder.ts` with base64url | imani-apps | P0-FE-001 | pending | |
| P0-FE-003 | Implement `TokenFingerprinter.ts` (SHA-256) | imani-apps | P0-FE-002 | pending | |
| P0-FE-004 | Implement `RedemptionLock.ts` (mutex) | imani-apps | P0-FE-001 | pending | |
| P0-FE-005 | Implement `CryptoAdapter.ts` interface | imani-apps | P0-FE-001 | pending | |
| P0-FE-006 | Implement `BrowserCryptoAdapter.ts` | imani-apps | P0-FE-005 | pending | |
| P0-FE-007 | Create package `index.ts` exports | imani-apps | P0-FE-003, P0-FE-004 | pending | |
| P0-FE-008 | Add `tsup.config.ts` and build | imani-apps | P0-FE-007 | pending | |
| P0-FE-009 | Add unit tests (vitest) | imani-apps | P0-FE-008 | pending | |
| P0-FE-010 | Create `tokenSecurityIntegration.js` | imani-apps | P0-FE-008 | pending | |
| P0-FE-011 | Update `storage.js` to use new fingerprint | imani-apps | P0-FE-010 | pending | |
| P0-FE-012 | Update `dmPoll.js` to use `TokenRedemption.redeem()` | imani-apps | P0-FE-010 | pending | |
| P0-FE-013 | Update `receive.js` to use `TokenRedemption.redeem()` | imani-apps | P0-FE-010 | pending | |
| P0-FE-014 | Update HTML files to load integration script | imani-apps | P0-FE-010 | pending | |

#### 8.2.2 Backend Gateway (cashu-client)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P0-BE-001 | Add migration `V3__add_proof_unique_constraint.sql` | cashu-client | - | pending | |
| P0-BE-002 | Update `ProofRepository.storeProofIfNotExists()` | cashu-client | P0-BE-001 | pending | |
| P0-BE-003 | Add duplicate detection logging | cashu-client | P0-BE-002 | pending | |
| P0-BE-004 | Add integration tests for duplicate handling | cashu-client | P0-BE-003 | pending | |

#### 8.2.3 Mint Server (cashu-mint)

| ID | Description | Project | Dependency | Status | Commit | Notes |
|----|-------------|---------|------------|--------|--------|-------|
| P0-MINT-001 | Add migration for spent_secrets unique constraint | cashu-mint | - | done | cashu-vault V2 | Migration exists in cashu-vault module as V2__add_proof_unique_constraint.sql |
| P0-MINT-002 | Add proof locking to `SwapTask` | cashu-mint | - | done | 2a5fd81 | Uses ProofLockManager.lockSecrets() for SERIALIZABLE-like behavior |
| P0-MINT-003 | Add `@Retryable` for serialization failures | cashu-mint | P0-MINT-002 | N/A | - | Not needed: ProofLockManager provides serialization, DB constraint is fallback |
| P0-MINT-004 | Add integration tests for concurrent swaps | cashu-mint | P0-MINT-002 | done | 2a5fd81 | SwapTaskConcurrencyTest with 5 test cases |

#### 8.2.4 Token Vault (cashu-vault)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P0-VAULT-001 | Add migration for proof uniqueness constraint | cashu-vault | - | pending | |
| P0-VAULT-002 | Add fingerprint column to wallet_proofs | cashu-vault | P0-VAULT-001 | pending | |
| P0-VAULT-003 | Update `ProofRepository.insertIfNotExists()` | cashu-vault | P0-VAULT-002 | pending | |
| P0-VAULT-004 | Add integration tests | cashu-vault | P0-VAULT-003 | pending | |

---

### 8.3 Phase 2: Idempotency & Hardening (P1)

Network-level duplicate prevention and API hardening.

#### 8.3.1 Frontend (imani-apps)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P1-FE-001 | Add `generateIdempotencyKey()` to api.js | imani-apps | P0-FE-010 | pending | |
| P1-FE-002 | Update `api.receive()` to send idempotency header | imani-apps | P1-FE-001 | pending | |
| P1-FE-003 | Create `submit-button.js` web component | imani-apps | - | pending | |
| P1-FE-004 | Update receive.html to use submit-button | imani-apps | P1-FE-003 | pending | |
| P1-FE-005 | Update send.html to use submit-button | imani-apps | P1-FE-003 | pending | |
| P1-FE-006 | Update scan pages to use submit-button | imani-apps | P1-FE-003 | pending | |

#### 8.3.2 Backend Gateway (cashu-client)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P1-BE-001 | Create `IdempotencyStore.java` interface | cashu-client | - | pending | |
| P1-BE-002 | Create `InMemoryIdempotencyStore.java` impl | cashu-client | P1-BE-001 | pending | |
| P1-BE-003 | Create `IdempotencyFilter.java` | cashu-client | P1-BE-002 | pending | |
| P1-BE-004 | Register filter in security config | cashu-client | P1-BE-003 | pending | |
| P1-BE-005 | Add SERIALIZABLE to `ProofService.receiveProofs()` | cashu-client | - | pending | |
| P1-BE-006 | Add `@Retryable` for serialization failures | cashu-client | P1-BE-005 | pending | |
| P1-BE-007 | Update `Nip98AuthenticationFilter` for payload hash | cashu-client | - | pending | |
| P1-BE-008 | Wrap request with `ContentCachingRequestWrapper` | cashu-client | P1-BE-007 | pending | |
| P1-BE-009 | Add integration tests for idempotency | cashu-client | P1-BE-004 | pending | |
| P1-BE-010 | Add integration tests for NIP-98 payload | cashu-client | P1-BE-008 | pending | |

#### 8.3.3 Wallet Protocol (cashu-wallet)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P1-WALLET-001 | Update dependency to cashu-lib 0.13.0 | cashu-wallet | P0-LIB-005 | pending | |
| P1-WALLET-002 | Add `verifyProofsUnspent()` with NUT-07 | cashu-wallet | P1-WALLET-001 | pending | |
| P1-WALLET-003 | Add `safeDeleteProof()` with state check | cashu-wallet | P1-WALLET-002 | pending | |
| P1-WALLET-004 | Add unit tests for state verification | cashu-wallet | P1-WALLET-003 | pending | |

---

### 8.4 Phase 3: Storage Security (P2)

XSS mitigation and secure storage.

#### 8.4.1 Frontend (imani-apps)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P2-FE-001 | Create `secureStorage.js` with IndexedDB | imani-apps | - | pending | |
| P2-FE-002 | Implement AES-GCM encryption | imani-apps | P2-FE-001 | pending | |
| P2-FE-003 | Implement PBKDF2 key derivation (600k iter) | imani-apps | P2-FE-002 | pending | |
| P2-FE-004 | Create migration from localStorage | imani-apps | P2-FE-003 | pending | |
| P2-FE-005 | Update auth to use secureStorage | imani-apps | P2-FE-004 | pending | |
| P2-FE-006 | Add NUT-07 state check in `safeDeleteProof()` | imani-apps | - | pending | |
| P2-FE-007 | Update proof deletion to use safe method | imani-apps | P2-FE-006 | pending | |

#### 8.4.2 Infrastructure (nginx/backend)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P2-INFRA-001 | Add CSP headers to nginx.conf | imani-apps | - | pending | |
| P2-INFRA-002 | Add X-Content-Type-Options header | imani-apps | P2-INFRA-001 | pending | |
| P2-INFRA-003 | Add X-Frame-Options header | imani-apps | P2-INFRA-001 | pending | |
| P2-INFRA-004 | Add Referrer-Policy header | imani-apps | P2-INFRA-001 | pending | |
| P2-INFRA-005 | Test CSP with all app features | imani-apps | P2-INFRA-004 | pending | |

#### 8.4.3 Voucher Management (cashu-voucher)

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P2-VOUCHER-001 | Update dependency to cashu-lib 0.13.0 | cashu-voucher | P0-LIB-005 | pending | |
| P2-VOUCHER-002 | Create `VoucherFingerprint.java` | cashu-voucher | P2-VOUCHER-001 | pending | |
| P2-VOUCHER-003 | Add `voucher_redemptions` table migration | cashu-voucher | - | pending | |
| P2-VOUCHER-004 | Update redemption service with fingerprint | cashu-voucher | P2-VOUCHER-002, P2-VOUCHER-003 | pending | |
| P2-VOUCHER-005 | Add integration tests | cashu-voucher | P2-VOUCHER-004 | pending | |

---

### 8.5 Phase 4: Advanced Security (P3)

Future enhancements and research.

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| P3-001 | Research WebAuthn PRF browser support | imani-apps | - | pending | |
| P3-002 | Prototype `webauthnPrf.js` | imani-apps | P3-001 | pending | |
| P3-003 | Design fallback for unsupported browsers | imani-apps | P3-002 | pending | |
| P3-004 | Design NIP-60 key wrapping pattern | imani-apps | - | pending | |
| P3-005 | Implement key wrapping prototype | imani-apps | P3-004 | pending | |
| P3-006 | User migration guide documentation | imani-apps | P3-003, P3-005 | pending | |

---

### 8.6 Deployment & Validation

| ID | Description | Project | Dependency | Status | Commit |
|----|-------------|---------|------------|--------|--------|
| DEPLOY-001 | Deploy P0 changes to staging | all | P0-* complete | pending | |
| DEPLOY-002 | Run security test suite on staging | all | DEPLOY-001 | pending | |
| DEPLOY-003 | Penetration testing (double-click, race) | all | DEPLOY-002 | pending | |
| DEPLOY-004 | Deploy P0 to production (feature flag) | all | DEPLOY-003 | pending | |
| DEPLOY-005 | Monitor for duplicate detection logs | all | DEPLOY-004 | pending | |
| DEPLOY-006 | Remove feature flag, full rollout | all | DEPLOY-005 | pending | |
| DEPLOY-007 | Deploy P1 changes to staging | all | P1-* complete | pending | |
| DEPLOY-008 | Deploy P1 to production | all | DEPLOY-007 | pending | |
| DEPLOY-009 | Deploy P2 changes to staging | all | P2-* complete | pending | |
| DEPLOY-010 | Deploy P2 to production | all | DEPLOY-009 | pending | |

---

### 8.7 Summary Statistics

| Phase | Project | Total Tasks | Pending | In Progress | Done |
|-------|---------|-------------|---------|-------------|------|
| P0-Foundation | cashu-lib | 5 | 5 | 0 | 0 |
| P0-Hotfix | imani-apps | 14 | 14 | 0 | 0 |
| P0-Hotfix | cashu-client | 4 | 4 | 0 | 0 |
| P0-Hotfix | cashu-mint | 4 | 4 | 0 | 0 |
| P0-Hotfix | cashu-vault | 4 | 4 | 0 | 0 |
| P1-Idempotency | imani-apps | 6 | 6 | 0 | 0 |
| P1-Idempotency | cashu-client | 10 | 10 | 0 | 0 |
| P1-Idempotency | cashu-wallet | 4 | 4 | 0 | 0 |
| P2-Storage | imani-apps | 7 | 7 | 0 | 0 |
| P2-Storage | infrastructure | 5 | 5 | 0 | 0 |
| P2-Storage | cashu-voucher | 5 | 5 | 0 | 0 |
| P3-Advanced | imani-apps | 6 | 6 | 0 | 0 |
| Deployment | all | 10 | 10 | 0 | 0 |
| **TOTAL** | | **84** | **84** | **0** | **0** |

---

## Appendix A: File Change Summary

### Frontend (imani-apps)

| File | Action | Phase |
|------|--------|-------|
| `packages/token-security/` | CREATE | P0 |
| `packages/token-security/src/core/TokenDecoder.ts` | CREATE | P0 |
| `packages/token-security/src/core/TokenFingerprinter.ts` | CREATE | P0 |
| `packages/token-security/src/core/RedemptionLock.ts` | CREATE | P0 |
| `packages/token-security/src/adapters/CryptoAdapter.ts` | CREATE | P0 |
| `packages/token-security/src/defaults/BrowserCryptoAdapter.ts` | CREATE | P0 |
| `lib/token-security.browser.min.js` | CREATE (built) | P0 |
| `shared/tokenSecurityIntegration.js` | CREATE | P0 |
| `shared/storage.js` | MODIFY | P0 |
| `shared/dmPoll.js` | MODIFY | P0 |
| `shared/api.js` | MODIFY | P1 |
| `shared/components/submit-button.js` | CREATE | P1 |
| `shared/secureStorage.js` | CREATE | P2 |
| `shared/webauthnPrf.js` | CREATE | P3 |

### Backend (cashu-client)

| File | Action | Phase |
|------|--------|-------|
| `cashu-client/.../V3__add_proof_unique_constraint.sql` | CREATE | P0 |
| `cashu-client/.../ProofRepository.java` | MODIFY | P0 |
| `cashu-client/.../IdempotencyFilter.java` | CREATE | P1 |
| `cashu-client/.../IdempotencyStore.java` | CREATE | P1 |
| `cashu-client/.../ProofService.java` | MODIFY | P1 |
| `cashu-client/.../Nip98AuthenticationFilter.java` | MODIFY | P1 |

---

## Appendix B: Reference Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           SECURITY LAYERS                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐         │
│  │  UI LAYER       │    │  API LAYER      │    │  DATA LAYER     │         │
│  ├─────────────────┤    ├─────────────────┤    ├─────────────────┤         │
│  │ submit-button   │    │ NIP-98 Auth     │    │ SERIALIZABLE    │         │
│  │ (double-click)  │    │ (replay check)  │    │ (isolation)     │         │
│  │                 │    │                 │    │                 │         │
│  │ Fingerprint     │    │ Idempotency     │    │ UNIQUE          │         │
│  │ (client check)  │    │ (server check)  │    │ (constraint)    │         │
│  │                 │    │                 │    │                 │         │
│  │ Global Mutex    │    │ Payload Hash    │    │ ON CONFLICT     │         │
│  │ (race prevent)  │    │ (integrity)     │    │ (upsert)        │         │
│  └────────┬────────┘    └────────┬────────┘    └────────┬────────┘         │
│           │                      │                      │                   │
│           └──────────────────────┼──────────────────────┘                   │
│                                  │                                          │
│                         ┌────────▼────────┐                                 │
│                         │  DEFENSE IN     │                                 │
│                         │  DEPTH          │                                 │
│                         │                 │                                 │
│                         │  Every layer    │                                 │
│                         │  prevents       │                                 │
│                         │  duplication    │                                 │
│                         └─────────────────┘                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Appendix C: Cross-Project Security Changes

The security implementation spans multiple projects in the Cashu ecosystem. This appendix documents required changes beyond imani-apps and cashu-client.

### C.1 Project Dependency Graph

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CASHU PROJECT ECOSYSTEM                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐                                                        │
│  │  imani-apps     │ ◄─── Frontend (this plan covers)                      │
│  │  (TypeScript/JS)│                                                        │
│  └────────┬────────┘                                                        │
│           │ REST API                                                        │
│           ▼                                                                 │
│  ┌─────────────────┐                                                        │
│  │  cashu-client   │ ◄─── Gateway API (this plan covers)                   │
│  │  (Java/Spring)  │      - gateway-plugin (NIP-98, idempotency)           │
│  │                 │      - wallet-plugin (proof storage)                  │
│  └────────┬────────┘                                                        │
│           │ depends on                                                      │
│           ▼                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐         │
│  │  cashu-lib      │    │  cashu-wallet   │    │  cashu-voucher  │         │
│  │  (Core library) │◄───│  (Wallet proto) │◄───│  (Voucher mgmt) │         │
│  │                 │    │                 │    │                 │         │
│  └────────┬────────┘    └─────────────────┘    └─────────────────┘         │
│           │                                                                 │
│           ▼                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                                │
│  │  cashu-mint     │───►│  cashu-vault    │                                │
│  │  (Mint server)  │    │  (Token store)  │                                │
│  │                 │    │                 │                                │
│  └─────────────────┘    └─────────────────┘                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### C.2 cashu-lib Changes (Core Library)

**Project:** `/home/eric/IdeaProjects/cashu-lib`
**Purpose:** Canonical implementation of fingerprinting algorithm that all projects use

#### C.2.1 Add Proof Fingerprinting Utility

**File:** `cashu-lib-crypto/src/main/java/xyz/tcheeric/cashu/crypto/ProofFingerprint.java` (NEW)

```java
package xyz.tcheeric.cashu.crypto;

import lombok.experimental.UtilityClass;
import xyz.tcheeric.cashu.common.Proof;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cryptographic fingerprinting for Cashu proofs.
 *
 * Provides collision-resistant fingerprints based on proof secrets,
 * ensuring different tokens never produce the same fingerprint.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/00.md">NUT-00</a>
 */
@UtilityClass
public class ProofFingerprint {

    /**
     * Computes canonical fingerprint for a list of proofs.
     *
     * Algorithm:
     * 1. Extract all proof secrets
     * 2. Sort lexicographically
     * 3. Join with '|' separator
     * 4. Append '||' + mintUrl
     * 5. SHA-256 hash the result
     *
     * @param proofs List of proofs to fingerprint
     * @param mintUrl Mint URL for additional uniqueness
     * @return 64-character hex fingerprint
     */
    public String computeFingerprint(List<Proof> proofs, String mintUrl) {
        if (proofs == null || proofs.isEmpty()) {
            throw new IllegalArgumentException("Proofs list cannot be null or empty");
        }

        String secrets = proofs.stream()
            .map(Proof::getSecret)
            .filter(s -> s != null && !s.isBlank())
            .sorted()
            .collect(Collectors.joining("|"));

        if (secrets.isEmpty()) {
            throw new IllegalArgumentException("No valid secrets found in proofs");
        }

        String input = secrets + "||" + (mintUrl != null ? mintUrl : "");
        return sha256Hex(input);
    }

    /**
     * Computes fingerprint for a single proof.
     */
    public String computeFingerprint(Proof proof, String mintUrl) {
        return computeFingerprint(List.of(proof), mintUrl);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

#### C.2.2 Add Token Fingerprinting for V3/V4 Tokens

**File:** `cashu-lib-common/src/main/java/xyz/tcheeric/cashu/common/TokenFingerprint.java` (NEW)

```java
package xyz.tcheeric.cashu.common;

import xyz.tcheeric.cashu.crypto.ProofFingerprint;

import java.util.List;

/**
 * Fingerprinting for complete Cashu tokens (V3/V4).
 */
public class TokenFingerprint {

    /**
     * Computes fingerprint for a Token object.
     * Delegates to ProofFingerprint for the actual computation.
     */
    public static String compute(Token token) {
        List<Proof> allProofs = token.getTokenData().stream()
            .flatMap(td -> td.getProofs().stream())
            .toList();

        String mintUrl = token.getTokenData().isEmpty() ? ""
            : token.getTokenData().get(0).getMint();

        return ProofFingerprint.computeFingerprint(allProofs, mintUrl);
    }
}
```

**Rationale:**
- Single source of truth for fingerprinting algorithm
- All projects import from cashu-lib for consistency
- Uses proof secrets (not keyset ID) to avoid NUT-13 collision issues

---

### C.3 cashu-mint Changes (Mint Server)

**Project:** `/home/eric/IdeaProjects/cashu-mint`
**Purpose:** Double-spend prevention at the mint level

#### C.3.1 Database Constraint on Spent Secrets

**File:** `cashu-mint-protocol/src/main/resources/db/migration/V__add_spent_secret_constraint.sql` (NEW)

```sql
-- Spent secrets table must have unique constraint
-- This is the FINAL line of defense against double-spend

-- Ensure spent_secrets table has unique constraint on secret
ALTER TABLE spent_secrets
ADD CONSTRAINT uk_spent_secrets_secret
UNIQUE (secret);

-- Index for fast lookups during swap/melt operations
CREATE INDEX IF NOT EXISTS idx_spent_secrets_secret
ON spent_secrets(secret);

COMMENT ON CONSTRAINT uk_spent_secrets_secret ON spent_secrets IS
'Prevents double-spend. Each secret can only be spent once across all mints.';
```

#### C.3.2 SERIALIZABLE Isolation for Swap/Melt

**File:** `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/service/SwapService.java` (MODIFY)

```java
import org.springframework.transaction.annotation.Isolation;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

/**
 * Swap service with SERIALIZABLE isolation to prevent race conditions.
 */
@Service
@RequiredArgsConstructor
public class SwapService {

    @Transactional(isolation = Isolation.SERIALIZABLE)
    @Retryable(
        retryFor = {SerializationFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public SwapResponse swap(SwapRequest request) {
        // 1. Verify all input proofs are unspent
        // 2. Mark input proofs as spent (atomically)
        // 3. Generate new proofs for outputs
        // 4. Return new proofs
    }
}
```

#### C.3.3 NUT-07 State Tracking

**File:** `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/model/ProofState.java` (NEW if not exists)

```java
/**
 * Proof states per NUT-07.
 *
 * State transitions:
 * - UNSPENT: Proof exists and is valid for spending
 * - PENDING: Proof is in a pending operation (melt in progress)
 * - SPENT: Proof has been spent and cannot be reused
 */
public enum ProofState {
    UNSPENT,
    PENDING,
    SPENT
}
```

---

### C.4 cashu-vault Changes (Token Vault)

**Project:** `/home/eric/IdeaProjects/cashu-vault`
**Purpose:** Secure proof storage with uniqueness guarantees

#### C.4.1 Unique Constraint Migration

**File:** `cashu-vault-jpa/src/main/resources/db/migration/V__add_proof_uniqueness.sql` (NEW)

```sql
-- Proofs stored in vault must be unique by commitment (C)
-- This prevents storing the same proof twice

ALTER TABLE wallet_proofs
ADD CONSTRAINT uk_wallet_proofs_commitment
UNIQUE (mint_url, commitment);

-- Add fingerprint column for fast duplicate detection
ALTER TABLE wallet_proofs
ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_wallet_proofs_fingerprint
ON wallet_proofs(fingerprint);
```

#### C.4.2 Proof Repository with Duplicate Detection

**File:** `cashu-vault-jpa/src/main/java/xyz/tcheeric/cashu/vault/repository/ProofRepository.java` (MODIFY)

```java
/**
 * Stores proof with duplicate detection using ON CONFLICT.
 * Returns false if proof already exists (idempotent operation).
 */
@Modifying
@Query(value = """
    INSERT INTO wallet_proofs (mint_url, commitment, amount, secret_enc, keyset_id, fingerprint)
    VALUES (:mintUrl, :commitment, :amount, :secretEnc, :keysetId, :fingerprint)
    ON CONFLICT (mint_url, commitment) DO NOTHING
    """, nativeQuery = true)
int insertIfNotExists(
    @Param("mintUrl") String mintUrl,
    @Param("commitment") String commitment,
    @Param("amount") Long amount,
    @Param("secretEnc") String secretEnc,
    @Param("keysetId") String keysetId,
    @Param("fingerprint") String fingerprint
);
```

---

### C.5 cashu-wallet Changes (Wallet Protocol)

**Project:** `/home/eric/IdeaProjects/cashu-wallet`
**Purpose:** Safe proof management with state verification

#### C.5.1 NUT-07 State Check Before Spend

**File:** `cashu-wallet-client/src/main/java/xyz/tcheeric/cashu/wallet/service/ProofService.java` (MODIFY)

```java
/**
 * Verifies proofs are still unspent before attempting to spend.
 *
 * @throws ProofAlreadySpentException if any proof is spent
 */
public void verifyProofsUnspent(List<Proof> proofs, String mintUrl) {
    CheckStateRequest request = new CheckStateRequest(
        proofs.stream().map(Proof::getSecret).toList()
    );

    CheckStateResponse response = mintClient.checkState(mintUrl, request);

    List<String> spentSecrets = response.getStates().entrySet().stream()
        .filter(e -> e.getValue() == ProofState.SPENT)
        .map(Map.Entry::getKey)
        .toList();

    if (!spentSecrets.isEmpty()) {
        throw new ProofAlreadySpentException(
            "Proofs already spent: " + spentSecrets.size() + " of " + proofs.size()
        );
    }
}
```

#### C.5.2 Safe Proof Deletion

```java
/**
 * Deletes proof only after verifying it's spent.
 * Prevents accidental deletion of unspent proofs.
 */
public boolean safeDeleteProof(Proof proof, String mintUrl) {
    CheckStateResponse state = mintClient.checkState(
        mintUrl,
        new CheckStateRequest(List.of(proof.getSecret()))
    );

    if (state.getStates().get(proof.getSecret()) != ProofState.SPENT) {
        log.warn("Refusing to delete unspent proof: {}",
            proof.getSecret().substring(0, 16) + "...");
        return false;
    }

    proofRepository.delete(proof);
    return true;
}
```

---

### C.6 cashu-voucher Changes (Voucher Management)

**Project:** `/home/eric/IdeaProjects/cashu-voucher`
**Purpose:** Voucher issuance/redemption with duplicate protection

#### C.6.1 Voucher Fingerprinting

```java
/**
 * Vouchers should store fingerprints for duplicate detection.
 * Uses the underlying Cashu token fingerprint.
 */
public class VoucherFingerprint {

    public static String compute(Voucher voucher) {
        // Decode the Cashu token from voucher
        Token token = TokenCodec.decode(voucher.getTokenString());
        return TokenFingerprint.compute(token);
    }
}
```

#### C.6.2 Redemption Lock Table

```sql
-- Track voucher redemptions with fingerprints
CREATE TABLE IF NOT EXISTS voucher_redemptions (
    id BIGSERIAL PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    redeemed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    redeemed_by VARCHAR(64) NOT NULL,
    voucher_id BIGINT REFERENCES vouchers(id)
);

CREATE INDEX idx_voucher_redemptions_fingerprint
ON voucher_redemptions(fingerprint);
```

---

### C.7 Summary: Cross-Project File Changes

| Project | File | Action | Description |
|---------|------|--------|-------------|
| **cashu-lib** | `ProofFingerprint.java` | CREATE | Canonical fingerprinting algorithm |
| **cashu-lib** | `TokenFingerprint.java` | CREATE | Token-level fingerprinting |
| **cashu-mint** | `V__spent_secret_constraint.sql` | CREATE | Unique constraint on spent secrets |
| **cashu-mint** | `SwapService.java` | MODIFY | SERIALIZABLE isolation |
| **cashu-vault** | `V__proof_uniqueness.sql` | CREATE | Unique constraint on proofs |
| **cashu-vault** | `ProofRepository.java` | MODIFY | ON CONFLICT handling |
| **cashu-wallet** | `ProofService.java` | MODIFY | NUT-07 state checks |
| **cashu-voucher** | `VoucherFingerprint.java` | CREATE | Voucher fingerprinting |
| **cashu-voucher** | `voucher_redemptions.sql` | CREATE | Redemption tracking table |

---

### C.8 Implementation Order

The cross-project changes should be implemented in this order due to dependencies:

```
Phase 1: cashu-lib (0.13.0)
├── Add ProofFingerprint utility
├── Add TokenFingerprint utility
├── Publish to Maven Central
└── Update all dependent project POMs

Phase 2: cashu-mint (0.10.0)
├── Add database constraint migration
├── Add SERIALIZABLE isolation
└── Verify NUT-07 state tracking

Phase 3: cashu-vault (0.5.0)
├── Add proof uniqueness constraint
├── Add fingerprint column
└── Update repository methods

Phase 4: cashu-wallet (0.5.0)
├── Add NUT-07 state checks
├── Add safe deletion logic
└── Import cashu-lib fingerprinting

Phase 5: cashu-voucher (0.6.0)
├── Add voucher fingerprinting
├── Add redemption tracking
└── Update redemption service

Phase 6: cashu-client (0.7.0)
├── Update dependency versions
├── Integrate new fingerprinting
└── Run integration tests
```

---

**Document Status:** Draft
**Author:** Claude (AI Assistant)
**Review Required By:** Engineering Lead
**Approval Required By:** Security Team
