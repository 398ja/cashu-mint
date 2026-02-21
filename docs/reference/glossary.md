# Glossary

This reference defines terms used throughout the cashu-mint documentation and codebase.

## Protocol Concepts

**BDHKE (Blind Diffie-Hellman Key Exchange)**
The cryptographic scheme underlying Cashu. A user blinds a message, the mint signs it without seeing the content, and the user unblinds the signature to obtain a valid token.

**BlindedMessage**
A cryptographically blinded value sent by a wallet to the mint during minting or swapping. The mint signs it without learning the underlying secret. Represented as `B_` in protocol messages.

**BlindSignature**
The mint's signature on a blinded message. The wallet unblinds this to obtain a valid proof. Represented as `C_` in protocol messages.

**Denomination**
A power-of-two sat amount (1, 2, 4, 8, ..., 2^n) for which the mint holds a signing key within a keyset. Tokens are composed of proofs at these fixed denominations.

**DLEQ (Discrete Log Equality Proof)**
A zero-knowledge proof that a blind signature was created with the correct private key, without revealing the key. Specified in NUT-12.

**HTLC (Hash Time-Locked Contract)**
A spending condition where a proof can only be redeemed by presenting a preimage that hashes to a committed value, optionally with a time lock.

**KeySet**
A collection of public/private key pairs, one per denomination, used by the mint to sign tokens. Each keyset is identified by a unique keyset ID.

**KeySet ID**
A deterministic identifier derived from the keyset's public keys. Wallets use it to identify which keyset was used to sign a proof.

**Melt**
The act of destroying ecash tokens by returning proofs to the mint in exchange for a Lightning payment or other settlement. Specified in NUT-05.

**Melt Quote**
A promise from the mint to settle a Lightning invoice (or other payment) in exchange for proofs of sufficient value. The quote locks the payment terms before the wallet commits proofs.

**Mint (noun)**
The ecash issuer. Holds signing keys, creates blind signatures, and verifies proofs. Identified by a UUID in this implementation.

**Mint (verb)**
The act of creating new ecash tokens. A wallet pays an invoice, then presents blinded messages to the mint for signing. Specified in NUT-04.

**Mint Quote**
A request for the mint to issue tokens. The mint provides a Lightning invoice; once paid, the wallet can exchange blinded messages for blind signatures.

**NUT (Notation, Utilization, and Terminology)**
A numbered specification in the Cashu protocol. Each NUT defines a specific feature or interaction pattern (e.g., NUT-03 for swaps, NUT-04 for minting).

**P2PK (Pay-to-Public-Key)**
A spending condition where a proof can only be redeemed by the holder of a specific private key. The public key is embedded in the proof's secret.

**Proof**
A token ownership credential consisting of an amount, a secret, a key `C`, and a keyset ID. Proofs are verified by the mint during swap and melt operations.

**Quote**
A general term for either a mint quote or a melt quote. Quotes have states (UNPAID, PAID, ISSUED for mint; UNPAID, PENDING, PAID for melt).

**Secret**
A unique value embedded in a proof. The mint tracks secrets to prevent double-spending. Secrets may encode spending conditions (P2PK, HTLC).

**Spending Condition**
Additional rules encoded in a proof's secret that restrict who can redeem it (P2PK) or under what circumstances (HTLC, locktime).

**Swap**
Exchanging one set of proofs for another of equal value (minus fees). Used to change denominations, refresh token privacy, or split/combine amounts. Specified in NUT-03.

**Token**
A portable representation of ecash, consisting of one or more proofs and the mint URL. Tokens can be serialized for transfer between wallets.

**Unit**
The denomination unit for a keyset (e.g., `sat` for satoshis). Each keyset is associated with exactly one unit.

**Y-value**
The result of hashing a proof's secret to an elliptic curve point using hash-to-curve. Used as the canonical identifier for proof state lookups (NUT-07).

## Infrastructure Concepts

**BOLT11**
The Lightning Network invoice format. cashu-mint uses BOLT11 invoices as the primary payment method for minting and melting.

**Gateway**
An abstraction over payment backends (e.g., Lightning). Gateways handle invoice creation, payment status checks, and outgoing payments. Configured via `GATEWAY_{METHOD}_{UNIT}` environment variables.

**Vault**
The persistence layer for proofs, signatures, and keysets. cashu-mint communicates with the vault via REST API (`cashu-vault` service). The vault uses JPA/PostgreSQL internally.

**Voucher**
A gift-card-like token that uses structured secrets and Nostr publishing. Vouchers have special minting (percentage fee) and redemption (split-only swap) rules.

## Architecture Concepts

**Clean Architecture**
The architectural style used by this project. Business logic (protocol, tasks) has no dependency on infrastructure (REST, persistence). Dependencies point inward.

**Hexagonal Architecture**
Also called ports and adapters. The protocol layer defines ports (interfaces); infrastructure adapters implement them. This allows swapping persistence or payment backends without changing business logic.

**SPI (Service Provider Interface)**
An interface defined by the protocol layer and implemented by infrastructure modules. Examples include the vault SPI and gateway SPI.

**Task**
A protocol-layer object that encapsulates a multi-step workflow (e.g., `MintTask`, `SwapTask`, `MeltTask`). Tasks promote testability and single responsibility.

## See Also

- [Supported NUTs](nuts.md)
- [Architecture overview](../explanations/architecture-overview.md)
- [REST API reference](rest-api.md)
