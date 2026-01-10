# Changelog

All notable changes to the Cashu Mint will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Fixed

- Voucher issuance responses now include a generated `cashuA` token so REST clients and Nostr integration tests receive non-null voucher tokens.
- Integration test configuration now uses the token-enriching voucher service to keep Nostr voucher flows aligned with the REST behavior.

---

## [0.7.1] - 2026-01-10

### Changed

- Updated cashu-lib dependency from 0.10.0 to 0.11.1
- Updated cashu-gateway dependency from 0.4.8 to 0.5.0
- Updated cashu-voucher dependency from 0.4.0 to 0.5.0
- Updated cashu-wallet dependency from 0.4.2 to 0.4.4
- Updated cashu-client dependency from 1.2.7 to 1.2.8

---

## [0.7.0] - 2026-01-07

### Added

- **Voucher Tag Support**: Enhanced voucher proof verification with tag-based identification
  - Enables voucher proofs to be identified and processed using NUT-10 tags
  - Supports flexible voucher detection across swap and verification operations

### Changed

- Bumped Spring Boot to 3.5.6, Tomcat to 10.1.48, and Logback to 1.5.19 to pick up upstream security fixes.
- Replaced deprecated Prometheus configuration flags with the current `management.prometheus.metrics.export.enabled` property and documented the new setting.

### Fixed

- Resolved Qodana findings across protocol and REST modules: removed redundant exception handling, enforced non-null blinded messages, improved refund signature logging, hardened YAML property loading, tightened preload SQL path validation, and cleaned up unused variables.
- Added Spring configuration metadata for Phoenixd, webhook, and voucher flags so test property files resolve cleanly.

---

## [0.6.0] - 2026-01-06

### Added

- **VoucherSpendingCondition**: New spending condition for voucher proof verification
  - Uses dynamic key derivation (same as minting) for arbitrary voucher amounts
  - Enables voucher proofs with non-power-of-2 amounts to be verified and swapped
- **Voucher Mock Payment**: Voucher tokens now skip Lightning payment verification during minting
  - Vouchers are merchant IOUs with no real bitcoin backing
  - `VoucherQuoteRegistry.isVoucherQuote()` detects voucher quotes in `MintTask`
  - Audit logging tracks when mock payment is used
- **Mixed Proof Type Validation**: `SwapTask` rejects operations mixing voucher and regular proofs
  - `VoucherSecretDetector.isVoucherSecret()` identifies voucher proofs
  - Clear error message: `mixed_proof_types_error`
- **Arbitrary Voucher Denominations (Free Splitting)**: Vouchers can use any positive amount
  - No power-of-2 denomination constraint for voucher tokens
  - Enables free splitting (e.g., 100 → 33 + 67) without swap overhead
  - `VoucherKeyDerivation` provides HMAC-SHA256 based key derivation for arbitrary amounts
  - `VoucherMasterSecretConfig` configures the voucher master secret
- New unit tests for voucher mock payment behavior:
  - `MintTaskTest`: voucher quote skip payment, regular quote requires payment, arbitrary denominations
  - `SwapTaskTest`: mixed proof rejection, voucher-only swaps, non-power-of-2 splits
- New documentation: `docs/explanations/voucher-mock-payment.md`

### Changed

- `MintTask` now branches on `isVoucherQuote` for payment verification and denomination validation
- `SwapTask` validates proof types before processing and allows arbitrary output amounts for voucher swaps
- `SignBlindedMessageTask` supports voucher mode with dynamic key derivation
- `VerifyProofsTask` routes voucher proofs to `VoucherSpendingCondition` for dynamic key verification

### Fixed

- Voucher proof verification now uses dynamic key derivation matching minting
  - Previously, voucher proofs with arbitrary amounts (e.g., 33 sats) failed verification
  - The vault had no stored key for non-power-of-2 amounts, causing `verify_proof_key_set_not_found`
  - Now uses `VoucherKeyDerivation` to derive keys on-the-fly during verification

---

## [0.5.2] - 2025-12-28

### Changed

- Updated cashu-voucher dependency from 0.3.6 to 0.3.7
- Updated cashu-wallet dependency from 0.4.0 to 0.4.2
- Updated cashu-client dependency from 1.2.6 to 1.2.7

---

## [0.5.1] - 2025-12-23

### Added

- Built-in task instrumentation via `TaskExecutionRecorder`/`InstrumentedTask`, enabling task-level metrics even when protocol tasks are instantiated directly.

### Fixed

- Enforce Model B voucher rejection during swap and melt verification with clear `CashuErrorException` messages and safer melt proof checks.

### Changed

- Test infrastructure: enable Mockito inline mock maker with ByteBuddy agent to support static/constructor mocks in CI-friendly environments.
- Updated cashu-voucher dependency from 0.3.5 to 0.3.6
- Updated cashu-client dependency from 1.2.5 to 1.2.6
- Updated nostr-java dependency from 1.0.1 to 1.1.0

---

## [0.5.0] - 2025-12-22

### Added

- NUT-12 support enabled across the mint, aligning protocol and REST handling with the updated specification.
- Mint now generates and attaches DLEQ proofs to blind signatures with tests covering proof generation and attachment.

### Changed

- Bumped project version to 0.5.0 across all modules.

---

## [0.4.7] - 2025-12-21

### Added

- New explanation page for voucher mint percentage fees overview (`docs/explanations/voucher-mint-quote-overview.md`).

### Changed

- Upgraded project version to 0.4.7 across all modules.
- Moved voucher percentage fee implementation plan to `project/` to keep user-facing docs concise.

### Fixed

- Voucher Nostr integration tests now use a matching BIP-340 public key for the configured private key.
- Mockito inline/agent wiring stabilized to allow static and constructor mocks in tests.

---

## [0.4.3] - 2025-12-17

### Fixed

- **Voucher Swap Support**: Allow voucher proofs (NUT-10 VOUCHER secrets) in swap operations
  - Removed incorrect Model B enforcement from `VerifyProofsTask` - swapping is not redemption
  - Model B enforcement (controlling where vouchers can be redeemed for goods/services) belongs at the merchant/application layer, not the protocol layer
  - Added `VoucherWellKnownSecret` handling in `getSpendingCondition()` using `RSSSpendingCondition` (same BDHKE verification)

- **Proof Storage**: Fixed `MintProtocolUtil.toProofEntity()` to store Y coordinate instead of raw secret string
  - Was storing `proof.getSecret().toString()` which returns full NUT-10 JSON (992+ characters for vouchers)
  - Now uses `SecretUtil.toY()` to store 66-character hex Y coordinate
  - Fixes "value too long for type character varying(255)" database errors

- **VoucherSecretDetector**: Enhanced detection to recognize all voucher secret formats
  - Added check for `VoucherWellKnownSecret` (NUT-10 format from cashu-lib-common)
  - Added check for `WellKnownSecret` with `Kind.VOUCHER`
  - Maintains reflection-based check for optional `VoucherSecret` from cashu-voucher-domain

### Changed

- Updated to cashu-lib 0.7.2 for NUT-10 BDHKE verification fix

---

## [0.4.2] and earlier

See git history for earlier changes.
