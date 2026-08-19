# Archived keysets must refuse to sign

The active/archived flag on a keyset is currently advertising metadata only:
`ActiveKeySetsTask` uses it to build `/v1/keysets`, `MintLoadService.keySets()`
loads archived keysets alongside active ones, and `SignBlindedMessageTask`
resolves the private key from whatever keyset id the client supplied. Nothing in
the signing path reads the flag.

We decided archived must mean the mint refuses to sign new blinded messages
against that keyset, while continuing to verify and redeem existing proofs
indefinitely. Enforcement belongs in the signing path rather than in
controllers, so that every route to a signature is covered.

## Consequences

Before this, retiring a mint did not stop it signing: the admin archived the
vault keysets and the mint carried on issuing to any client that named the old
keyset id. Rotation could not be built without it either, because writing a new
keyset and archiving the old one would have left both signing.

Refusal must carry a distinct, wallet-actionable error, separable from "unknown
keyset" and from "quote not paid", because the correct wallet response —
re-read `/v1/keys` and retry — differs in each case.
