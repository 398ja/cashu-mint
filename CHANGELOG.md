# Changelog

All notable changes to the Cashu Mint will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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
