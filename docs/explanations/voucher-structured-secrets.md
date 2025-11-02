# Representing vouchers as structured secrets

Designing Cashu vouchers as structured secrets lets you enrich the blinded preimage with the voucher metadata that wallets and mints need while still speaking the standard Cashu protocol. The REST layer, protocol tasks, and vault services are already generic in the secret type, so you can swap in a richer implementation without changing endpoint contracts.

## Understand the existing secret contract

Every entry point that handles blinded outputs or proofs works with the `Secret` abstraction. For example, the public controller declares `CashuController<T extends Secret>`, forwards request payloads to NUT helpers, and never inspects the fields of `T` directly. `MintTask`, `MintTokensTask`, and the melt/swap tasks repeat the same pattern: they receive `Proof<T>` or `BlindedMessage<T>` objects, call the elliptic-curve signing routines, and return blind signatures that the client later unblinds. This indirection means that as long as your voucher secret can produce the usual hash-to-curve scalar and recover its payload after unblinding, the rest of the mint remains agnostic to the extra structure.

Key expectations for a custom secret implementation:

- **Deterministic encoding.** The value you blind must derive from a canonical encoding of the voucher so the redeemer recovers the exact metadata after unblinding. Use a compact binary representation (CBOR, MessagePack) or a fixed-field JSON layout.
- **Hash-to-curve compatibility.** Implement the existing hash-to-curve flow so your secret produces the same scalar input that `SignBlindedMessageTask` expects. If you wrap an existing secret type, delegate to its scalar derivation logic.
- **Stable memo surface.** Decide whether the voucher metadata lives in the token memo, inside the secret payload, or split across both. Expose helper methods that the wallet can call to extract the memo after unblinding.

## Model the voucher secret

Create a dedicated type (record, class, or sealed hierarchy) that implements `Secret` and carries:

- Core Cashu parameters (the random preimage, derivation seed, or point data required by the hash-to-curve step).
- Voucher metadata such as voucher identifier, base currency, face value, expiry, issuer signature, and any redemption constraints.
- Optional integrity payloads (e.g., issuer signature over the voucher body, hash commitments to off-chain documents, or encryption envelopes for private terms).

Organise the fields into a canonical order and provide builders or factories that validate the voucher rules before producing the blinded preimage. Wallets that mint vouchers can then instantiate this secret, fill in voucher metadata, and hand the blinded output to the mint API unchanged.

## Wire the secret through the mint workflow

Because `CashuController` and the protocol tasks are parameterised by `T extends Secret`, you introduce the voucher-aware type by:

1. Extending the REST DTOs so that `PostMintRequest<T>` (and related swap/melt requests) are deserialised with your voucher secret. In Spring Boot this usually means registering a `Module` with Jackson to map the request JSON onto your implementation or wrapping the voucher secret inside an existing secret DTO.
2. Ensuring that `MintTokensTask` and `MintTask` receive the new secret without additional casting. They will continue to call the common mint protocol functions, generate blind signatures, and echo your metadata back in the unblinded proofs.
3. Updating wallet-side code to unblind the signatures and reconstruct the voucher secret. The memo payload will survive the round trip, so the redeemer can inspect voucher terms before spending or melting.

If the mint should enforce voucher rules (expiry, issuer allowlist, base currency), add validation hooks before calling `SignBlindedMessageTask.execute()` so malformed or expired voucher secrets yield a structured `CashuErrorException`.

## Versioning and interoperability

Publish the voucher schema alongside your mint metadata (for example through NUT-06 URLs or documentation) so third-party wallets can construct the same structured secret. Include a schema version inside the secret to allow future evolution, and keep old versions available for existing vouchers. Finally, document how the voucher metadata maps to the blinded preimage so wallet developers can verify compatibility during integration.
