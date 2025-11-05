# Voucher Structured Secrets - Enhanced Implementation Plan

This plan tracks the work required to implement voucher support across the Cashu stack in line with `docs/explanations/voucher-structured-secrets.md`.

## Task Sizing Guide

| Size | Estimated Effort | Examples |
|------|-----------------|----------|
| **XS** | < 2 hours | Add enum value, small config change, simple warning message |
| **S** | 2-4 hours | Add single method, simple endpoint, basic validation check |
| **M** | 1-2 days | Implement class with multiple methods, add configuration system, write moderate test suite |
| **L** | 3-5 days | Implement complex class with full API, build UI flow, comprehensive integration tests |
| **XL** | 1-2 weeks | Multiple interconnected components, full feature implementation, extensive testing |

**Priority Levels:**
- **P0**: Critical path, blocks other work, must have for MVP
- **P1**: Important for quality, should have for initial release
- **P2**: Nice to have, can be deferred to later releases

---

## Project Summary

| Phase | Tasks | XS | S | M | L | XL | Total Effort (days) | P0 Tasks |
|-------|-------|----|----|---|---|----|--------------------|----------|
| **Phase 1**: Core Library | 13 | 1 | 4 | 6 | 2 | 0 | ~15-20 days | 10 |
| **Phase 2**: Mint Service | 12 | 0 | 3 | 6 | 2 | 1 | ~20-25 days | 7 |
| **Phase 3**: Wallet/Client | 13 | 1 | 3 | 5 | 4 | 0 | ~25-30 days | 8 |
| **Phase 4**: Nostr Integration | 6 | 0 | 3 | 2 | 0 | 1 | ~10-12 days | 1 |
| **Phase 5**: Deployment | 10 | 1 | 1 | 5 | 2 | 1 | ~15-20 days | 4 |
| **TOTAL** | **54** | **3** | **14** | **24** | **10** | **3** | **~85-107 days** | **30** |

**Critical Path Estimate:** 8-12 weeks with dedicated team
**Parallel Development:** 6-8 weeks with library/mint/client teams working concurrently

---

## Phase 1 – Core Library Enablement (cashu-lib)

| ID | Task | Description | Size | Priority | Dependencies | Acceptance Criteria | Status | Owner | Commit |
|----|------|-------------|------|----------|--------------|---------------------|--------|-------|--------|
| 1.1 | Add `VOUCHER` to Kind enum | Extend `WellKnownSecret.Kind` enum with `VOUCHER` entry | XS | P0 | None | Enum compiles, no conflicts | TODO | Library | - |
| 1.2 | Implement `VoucherSecret` class | Create class extending `WellKnownSecret` with all tag getters/setters | L | P0 | 1.1 | All 10 tags (schemaVersion, issuerId, issuerPubKey, currency, faceValue, issuedAt, expiresAt, merchantId, termsHash, issuerSig) implemented | TODO | Library | - |
| 1.3 | Add canonical bytes method | Implement `getCanonicalBytesWithoutSignature()` that clones, removes issuerSig, sorts tags, returns JSON bytes | M | P0 | 1.2, 1.4 | Returns deterministic bytes for same input, excludes issuerSig | TODO | Library | - |
| 1.4 | Add clone helper | Implement private `cloneWithoutSignature()` method | S | P0 | 1.2 | Used by 1.3, preserves all other tags | TODO | Library | - |
| 1.5 | Implement UUID encoding | Add `uuidToDataField()` and `dataFieldToUuid()` static helper methods | S | P0 | None | Produces consistent hex-encoded data field (32 chars lowercase) | TODO | Library | - |
| 1.6 | Extend deserializer | Update `WellKnownSecretDeserializer` with VOUCHER case, instantiate `VoucherSecret` | M | P0 | 1.2 | Deserializes JSON to VoucherSecret correctly | TODO | Library | - |
| 1.7 | Add tag type conversion | Implement `convertVoucherTagValues()` for type conversion (Long/Integer/String) | M | P0 | 1.6 | Tags have correct Java types after deserialization | TODO | Library | - |
| 1.8 | Ensure sorted serialization | Modify serializer to sort tags by key name for canonical output | M | P0 | 1.6 | Same VoucherSecret always produces same JSON string | TODO | Library | - |
| 1.9 | Define error codes | Add 7 voucher error codes with enum values | S | P0 | None | Error codes enumerated, documented, with numeric values 40000-40006 | TODO | Library | - |
| 1.10 | Create validation helpers | Reusable utility functions for schema version, expiry, signature verification | M | P1 | 1.2, 1.9 | Helpers are stateless, well-tested, reusable by mint/client | TODO | Library | - |
| 1.11 | Write unit tests | Test getters/setters, canonical bytes, cloning, UUID encoding | L | P0 | 1.2-1.5 | 95%+ code coverage, all tests pass | TODO | Library | - |
| 1.12 | Create test vectors | Generate 3+ test vectors: valid, expired, invalid signature | M | P0 | 1.11 | JSON format with voucherJson, canonicalJsonBytes, expectedHashToCurve | TODO | Library | - |
| 1.13 | Update documentation | API docs for VoucherSecret, usage examples, migration guide | M | P1 | All above | README updated, Javadoc complete, examples provided | TODO | Library | - |

**Phase 1 Success Criteria:**
- [ ] VoucherSecret can be instantiated, serialized, deserialized
- [ ] Canonical JSON bytes are deterministic
- [ ] All tag types are correctly converted
- [ ] Test vectors pass on reference implementation
- [ ] No breaking changes to existing Secret implementations

---

## Phase 2 – Mint Service Integration (cashu-mint)

| ID | Task | Description | Size | Priority | Dependencies | Acceptance Criteria | Status | Owner | Commit |
|----|------|-------------|------|----------|--------------|---------------------|--------|-------|--------|
| 2.1 | Verify REST DTO compatibility | Test that `PostMintRequest<VoucherSecret>` deserializes correctly | S | P0 | Phase 1 | Existing SecretDeserializer handles VoucherSecret polymorphically | TODO | Mint | - |
| 2.2 | Implement VoucherSpendingCondition | Create spending condition validator with 6 checks: schema, expiry, signature, allowlist, currency, amount | L | P0 | Phase 1 | All validation checks implemented, throws correct error codes | TODO | Mint | - |
| 2.3 | Add allowlist configuration | Create `VoucherConfig` class with allowedIssuers set and issuerPublicKeys map | M | P0 | None | Operators can configure via application.yml or environment variables | TODO | Mint | - |
| 2.4 | Implement hybrid allowlist manager | Manager supporting core issuers (manual), recommended issuers (Nostr), threshold-based acceptance | L | P1 | 2.3 | Can query Nostr for recommendations, merge with core list | TODO | Mint | - |
| 2.5 | Add Nostr issuer discovery | Query kind:38174 (announcements) and kind:38001 (recommendations) from configured relays | M | P1 | 2.4 | Successfully fetches and parses Nostr events | TODO | Mint | - |
| 2.6 | Publish mint allowlist on Nostr | Publish kind:30078 event with mint's accepted issuers | M | P2 | 2.3 | Allowlist is discoverable on Nostr relays | TODO | Mint | - |
| 2.7 | Add NUT-06 voucherInfo extension | Extend mint info response with supported, schemaVersions, allowedIssuers URL, nostrAllowlist, validationRules | M | P0 | 2.3 | Wallets can query voucher support via GET /v1/info | TODO | Mint | - |
| 2.8 | Create /v1/voucher/issuers endpoint | HTTP endpoint returning allowed issuers with metadata | S | P1 | 2.3 | Returns JSON array of issuers with id, name, pubkey, addedAt, status | TODO | Mint | - |
| 2.9 | Update proof persistence | Verify ProofEntity stores VoucherSecret.toString() correctly as JSON | S | P0 | 2.1 | Proofs can be restored from database with full voucher metadata | TODO | Mint | - |
| 2.10 | Integrate validation into mint flow | Call VoucherSpendingCondition during pre-mint and swap operations | M | P0 | 2.2 | Validation runs before blind signature generation | TODO | Mint | - |
| 2.11 | Write integration tests | Test cases: mint, swap, expired rejection, unknown issuer, invalid signature | XL | P0 | 2.1-2.10 | All positive and negative test cases pass, 90%+ coverage | TODO | Mint | - |
| 2.12 | Add operator documentation | Document allowlist configuration, Nostr integration, validation policies | M | P1 | All above | Operators can configure and maintain issuer allowlists | TODO | Mint | - |

**Phase 2 Success Criteria:**
- [ ] Mints can accept and validate VoucherSecrets
- [ ] Allowlist configuration is flexible (manual, Nostr, hybrid)
- [ ] Validation errors return correct error codes
- [ ] NUT-06 advertises voucher support
- [ ] Integration tests cover all validation paths

---

## Phase 3 – Wallet & Client Experience (cashu-client)

| ID | Task | Description | Size | Priority | Dependencies | Acceptance Criteria | Status | Owner | Commit |
|----|------|-------------|------|----------|--------------|---------------------|--------|-------|--------|
| 3.1 | Build voucher creation flow | UI/API to create vouchers: collect metadata, set schemaVersion=1, generate UUID, sign with issuer key | L | P0 | Phase 1 | Users can create valid VoucherSecrets with all required fields | TODO | Client | - |
| 3.2 | Implement issuer key management | Secure storage for issuer private keys, signing interface | M | P0 | 3.1 | Issuer keys encrypted at rest, can sign vouchers | TODO | Client | - |
| 3.3 | Add mint compatibility check | Query mint's voucherInfo, verify schema version and issuer allowlist before mint attempt | M | P0 | Phase 2 | Wallets fail fast with helpful error if mint doesn't support issuer | TODO | Client | - |
| 3.4 | Implement voucher minting flow | Blind VoucherSecret, send PostMintRequest, unblind response, store Proof<VoucherSecret> | L | P0 | 3.1, 3.3 | Voucher proofs can be minted successfully | TODO | Client | - |
| 3.5 | Create voucher display UI | Show voucher metadata: ID, issuer, value, currency, expiry, merchant restrictions | M | P0 | 3.4 | Users can view all voucher details before redemption | TODO | Client | - |
| 3.6 | Add client-side signature verification | Verify issuerSignature using getCanonicalBytesWithoutSignature before redemption | S | P1 | Phase 1 | Invalid signatures detected before sending to mint | TODO | Client | - |
| 3.7 | Add expiry warnings | Display warnings for vouchers near expiry (e.g., < 7 days) | XS | P1 | 3.5 | Users warned about expiring vouchers with time remaining | TODO | Client | - |
| 3.8 | Implement voucher redemption flow | Load Proof<VoucherSecret>, create swap/melt request, handle error responses | L | P0 | 3.4-3.6 | Vouchers can be redeemed (swapped or melted), errors handled gracefully | TODO | Client | - |
| 3.9 | Update proof storage | Ensure VoucherSecret JSON preserved verbatim in database | S | P0 | 3.4 | Canonical JSON ordering maintained through save/restore | TODO | Client | - |
| 3.10 | Add backup/restore for vouchers | Include VoucherSecrets in backup exports, verify canonical JSON on import | M | P0 | 3.9 | Vouchers backed up and restored without corruption | TODO | Client | - |
| 3.11 | Build issuer discovery UI | Help users find which mints accept which issuers, browse Nostr announcements | L | P2 | Phase 2 | Users can discover compatible mints for their vouchers | TODO | Client | - |
| 3.12 | Write client-side tests | Unit tests for creation, validation, redemption flows | L | P0 | 3.1-3.10 | All flows tested, edge cases covered, 85%+ coverage | TODO | Client | - |
| 3.13 | Create user documentation | Guide on creating, redeeming vouchers, understanding issuer trust | M | P1 | All above | Users understand voucher lifecycle and trust model | TODO | Client | - |

**Phase 3 Success Criteria:**
- [ ] Users can create and redeem vouchers
- [ ] Issuer signature verification works
- [ ] Mint compatibility checks prevent errors
- [ ] Voucher metadata is displayed clearly
- [ ] Backup/restore preserves canonical JSON

---

## Phase 4 – Nostr Integration & Discovery

| ID | Task | Description | Size | Priority | Dependencies | Acceptance Criteria | Status | Owner | Commit |
|----|------|-------------|------|----------|--------------|---------------------|--------|-------|--------|
| 4.1 | Define Nostr event schemas | Document kind:38174 (issuer announcement) and kind:38001 (recommendation) structures | S | P0 | None | Schemas documented, examples provided, match docs | TODO | Ecosystem | - |
| 4.2 | Implement issuer announcement publishing | Issuers can publish kind:38174 with metadata, currencies, categories | M | P1 | 4.1 | Issuer announcements discoverable on Nostr relays | TODO | Issuer tools | - |
| 4.3 | Implement recommendation publishing | Users/mints can publish kind:38001 recommendations with ratings | S | P2 | 4.1 | Recommendations build web-of-trust, rating system works | TODO | Client/Mint | - |
| 4.4 | Create Nostr relay configuration | Allow mints to configure which relays to query for issuer data | S | P1 | Phase 2 | Operators can specify trusted relay list in config | TODO | Mint | - |
| 4.5 | Add key rotation support | Implement deprecated-key tag handling in kind:38174 events | M | P1 | 4.2 | Mints accept vouchers from deprecated keys during grace period | TODO | Mint | - |
| 4.6 | Build issuer registry browser | Web UI to explore issuer announcements and recommendations | XL | P2 | 4.1-4.3 | Users can browse all registered issuers, filter by category/currency | TODO | Ecosystem | - |

**Phase 4 Success Criteria:**
- [ ] Issuer announcements are published on Nostr
- [ ] Mints discover issuers via Nostr queries
- [ ] Web-of-trust recommendations work
- [ ] Key rotation is supported

---

## Phase 5 – Deployment & Ecosystem Alignment

| ID | Task | Description | Size | Priority | Dependencies | Acceptance Criteria | Status | Owner | Commit |
|----|------|-------------|------|----------|--------------|---------------------|--------|-------|--------|
| 5.1 | Plan release schedule | Coordinate library → mint → client release sequence with dates | M | P0 | Phases 1-3 | Release schedule published, communicated to ecosystem | TODO | Release mgmt | - |
| 5.2 | Publish test vectors | Distribute canonical test vectors to all implementations | S | P0 | Phase 1 | Test vectors available in GitHub repo and docs site | TODO | Library | - |
| 5.3 | Create migration guide | Document upgrade path for existing mints and wallets | L | P0 | All above | Clear migration instructions for operators and developers | TODO | Docs | - |
| 5.4 | Coordinate breaking changes | Announce any API changes, deprecation timelines, compatibility matrix | M | P0 | 5.1 | No surprise breakage, deprecation warnings 6+ months in advance | TODO | Release mgmt | - |
| 5.5 | Set up monitoring | Track voucher mint rate, validation error rate, issuer coverage, redemption success | M | P1 | Phases 2-3 | Dashboards show voucher adoption and health metrics | TODO | DevOps | - |
| 5.6 | Create feedback channels | Discord/GitHub discussions for issuer and operator feedback | XS | P1 | None | Community can report issues and request features | TODO | Product | - |
| 5.7 | Run beta program | Invite 5-10 mints and issuers to test before public launch | L | P1 | 5.1-5.3 | Beta participants validate in production-like environments, feedback collected | TODO | Product | - |
| 5.8 | Launch public release | Announce voucher support, publish blog posts, update docs, press release | M | P0 | 5.7 | Voucher feature is live, documented, and publicly announced | TODO | Marketing | - |
| 5.9 | Monitor rollout | Track adoption, error rates, operator feedback for 4 weeks post-launch | M | P0 | 5.8 | No critical issues, smooth adoption curve, metrics trending positive | TODO | Support | - |
| 5.10 | Post-launch hardening | Address defects, refine UX, optimize policies based on production feedback | XL | P1 | 5.9 | Backlog prioritized with learnings, v1.1 improvements planned | TODO | All teams | - |

**Phase 5 Success Criteria:**
- [ ] Staggered releases prevent ecosystem breakage
- [ ] Test vectors ensure cross-implementation compatibility
- [ ] Community feedback is positive
- [ ] No critical production issues
- [ ] Adoption metrics show usage growth

---

## Detailed Acceptance Criteria

### VoucherSecret Implementation Checklist
- [ ] All 10 tags implemented with getters/setters
- [ ] `getCanonicalBytesWithoutSignature()` returns deterministic bytes
- [ ] UUID encoding produces 32-char lowercase hex
- [ ] Deserialization handles all tag value types correctly
- [ ] Serialization sorts tags by key name
- [ ] Test vectors include valid, expired, and invalid-signature cases

### VoucherSpendingCondition Checklist
- [ ] Schema version check (reject unsupported versions)
- [ ] Expiry check (reject if expiresAt < now)
- [ ] Issuer signature verification (verify against issuerPubKey)
- [ ] Issuer allowlist check (reject if not in allowlist)
- [ ] Currency validation (check supported currencies)
- [ ] Amount validation (faceValue matches mint request)
- [ ] All checks throw correct error codes

### Mint Configuration Checklist
- [ ] Manual issuer allowlist configuration
- [ ] Nostr relay list configuration
- [ ] Schema version support range
- [ ] Expiry buffer period (optional)
- [ ] Currency support list
- [ ] Terms hash enforcement toggle

### Wallet UX Checklist
- [ ] Create voucher form with all metadata fields
- [ ] Display issuer information and trust indicators
- [ ] Show expiry date with warnings
- [ ] Verify mint compatibility before minting
- [ ] Display redemption restrictions (merchant, currency)
- [ ] Handle all validation errors gracefully

---

## Risk Mitigation

| Risk | Impact | Likelihood | Mitigation | Owner |
|------|--------|------------|------------|-------|
| Canonical JSON serialization differs across implementations | High | Medium | Publish test vectors, enforce tag sorting in spec | Library |
| Signature verification implementation bugs | High | Medium | Reuse existing crypto libraries, extensive testing | Library |
| Allowlist management complexity overwhelms operators | Medium | High | Provide default configs, Nostr automation, clear docs | Mint |
| Wallet UX confusion around issuer trust | Medium | Medium | Education materials, clear warnings, trust indicators | Client |
| Nostr relay availability/spam | Low | Medium | Fallback to HTTP endpoints, operator relay configuration | Mint |
| Schema version fragmentation | High | Low | Conservative schema evolution, long deprecation cycles | All |

---

## Success Metrics

**Technical Metrics:**
- 95%+ test coverage for voucher code paths
- < 1% validation error rate in production
- Zero critical security issues
- < 100ms additional latency for voucher validation

**Adoption Metrics:**
- 10+ issuers announce on Nostr within 3 months
- 25+ mints enable voucher support within 6 months
- 1000+ vouchers minted in first month
- 5+ ecosystem implementations (wallets, tools)

**Quality Metrics:**
- < 5 P0 bugs reported in first 3 months
- > 80% positive feedback from operators
- < 10% support ticket rate related to vouchers
- Zero protocol-breaking changes needed post-launch
