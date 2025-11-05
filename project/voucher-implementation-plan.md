# Voucher Structured Secrets Project Plan

This plan tracks the work required to implement voucher support across the Cashu stack in line with `docs/explanations/voucher-structured-secrets.md`.

## Phase 1 – Core Library Enablement

| Task | Description | Owners | Dependencies | Notes | Status | Commit |
|------|-------------|--------|--------------|-------|--------|--------|
| Define `VoucherSecret` in `cashu-lib` | Implement the `VOUCHER` kind, canonical serialization, schemaVersion tag helpers, and `getCanonicalBytesWithoutSignature()` | Library team | Existing WellKnownSecret infrastructure | Include unit tests and test vectors | TODO | - |
| Extend deserializers/serializers | Update `WellKnownSecretDeserializer` and serializers to register `VoucherSecret` and ensure sorted-tag output | Library team | Task: Define `VoucherSecret` | Coordinate with JSON mapper modules | TODO | - |
| Publish voucher error codes and validation helpers | Add reusable enums/utilities for issuer signature checks, schema guardrails, and expiry validation | Library team | Task: Define `VoucherSecret` | Surface in public API for mint/client reuse | TODO | - |
| Document library changes | Update library docs with voucher usage examples and migration notes | Library team | Tasks above | Sync release notes with downstream teams | TODO | - |

## Phase 2 – Mint Service Integration

| Task | Description | Owners | Dependencies | Notes | Status | Commit |
|------|-------------|--------|--------------|-------|--------|--------|
| Wire voucher secrets through REST DTOs | Ensure `PostMintRequest`, swap/melt payloads, and controllers deserialize `VoucherSecret` seamlessly | Mint team | Phase 1 completion | Verify backwards compatibility with existing clients | TODO | - |
| Implement `VoucherSpendingCondition` | Enforce schema version, expiry, issuer allowlist, currency policy, and signature verification | Mint team | Phase 1 helpers | Apply during pre-mint and swap workflows | TODO | - |
| Add operator configuration & discovery metadata | Provide issuer allowlist settings, schema support ranges, and publish NUT-06/Nostr updates | Mint team | Wiring tasks | Include operational documentation | TODO | - |
| Expand mint test suite | Cover positive/negative voucher flows and policy enforcement scenarios | Mint team | Previous tasks | Incorporate integration tests with canned vouchers | TODO | - |

## Phase 3 – Wallet & Client Experience

| Task | Description | Owners | Dependencies | Notes | Status | Commit |
|------|-------------|--------|--------------|-------|--------|--------|
| Build voucher issuance UI/API | Allow users/issuers to define voucher schemas, capture metadata, and sign secrets | Client team | Phase 1 completion | Provide presets and validation hints | TODO | - |
| Support voucher redemption | Load stored `Proof<VoucherSecret>`, present metadata, verify issuer signature, and redeem via swap/melt | Client team | Issuance UI/API | Handle mint error responses gracefully | TODO | - |
| Persist and backup structured secrets | Update storage, backup, and restore routines to keep canonical JSON intact | Client team | Issuance & redemption features | Add regression tests for canonical ordering | TODO | - |
| UX education & documentation | Explain voucher lifecycle, issuer trust, and redemption limits within the wallet | Client team | Feature completion | Coordinate copy with product/marketing | TODO | - |

## Phase 4 – Deployment & Ecosystem Alignment

| Task | Description | Owners | Dependencies | Notes | Status | Commit |
|------|-------------|--------|--------------|-------|--------|--------|
| Coordinate releases | Plan staggered releases for library, mint, and client updates | Release management | Phases 1–3 completion | Communicate breaking changes early | TODO | - |
| Update issuer/mint discovery feeds | Publish allowlists, schema versions, and issuer announcements via HTTP/Nostr | Mint & ecosystem teams | Phase 2 completion | Follow NIP-87/NIP-51 patterns | TODO | - |
| Monitor rollout and collect feedback | Track adoption metrics, error rates, and issuer feedback post-release | Product & support | Releases live | Feed learnings into backlog | TODO | - |
| Post-launch hardening | Address defects, optimize UX, and refine policies (expiry buffers, allowlist governance) | All teams | Feedback loop | Schedule follow-up maintenance sprints | TODO | - |
