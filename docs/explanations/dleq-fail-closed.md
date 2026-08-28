# Why the mint fails closed on DLEQ

This document explains why a NUT-12 DLEQ proof failure now fails the whole signing request,
and why the proof nonce is derived deterministically rather than drawn from the RNG.

## The guarantee the mint advertises

`GET /v1/info` advertises `"12": {"supported": true}`. A DLEQ proof is what lets a wallet check
that the mint signed its blinded message with the same private key `a` that backs the published
public key `A = a*G`. Without it, a mint can hand each user a different key and later
deanonymize them by which key verifies their token. DLEQ is what makes that detectable.

## Fail closed, not fail open

The signing path used to wrap proof generation in `catch (Exception)`, log a warning, and return
the blind signature with `dleq = null`. That is fail-open: a verifiable signature silently
becomes an unverifiable one, in production, with a WARN line as the only trace. Worse, the wallet
cannot tell that degradation apart from a mint that never supported NUT-12, so it has no basis on
which to refuse the token.

`SignBlindedMessageTask` now fails the request instead. If the mint cannot honour what it
advertises, the honest answer is an error, not a weaker token. The failure increments
`cashu_mint_dleq_generation_failures_total`, so the condition is alertable rather than buried in
logs. The counter is registered eagerly and reads `0` while signing is healthy, which is what
makes an alert on it armable.

The consequence is that every swap and mint response carries a `dleq`, because any response that
would not have carried one is now an error instead.

## Deterministic nonce

NUT-12 specifies:

```
r = HMAC-SHA256(key=a, data="Cashu_DLEQ_R_v1" || A || B' || C' || ctr)
```

with `ctr` starting at `0x00` and incrementing while `r == 0` or `r >= n`.

A random nonce satisfies the spec's **MUST** (a CSPRNG), but reusing one across two challenges
leaks the private key outright: `a = (s₁ - s₂) · (e₁ - e₂)⁻¹ mod n`. Deriving `r` from the key and
the message removes that failure mode entirely, since two different challenges cannot produce the
same `r`, and an RNG fault cannot produce one either. `DeterministicDLEQNonce` implements the
derivation with the rejection-sampling loop; `DefaultDLEQProofGenerator` computes `e` and `s` from
it and is checked against the published NUT-12 vector.

## Related

- [Supported NUTs](../reference/nuts.md)
- [NUT compliance audit](nut-compliance-audit.md), finding M7
- [Metrics reference](../../cashu-mint-observability/docs/metrics-reference.md)
