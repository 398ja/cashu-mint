# Run the interoperability test

This guide shows how to run the interoperability test that drives an external
Cashu implementation, [Nutshell](https://github.com/cashubtc/nutshell), through
mint → swap → melt against this mint, and how to read what it reports.

The test exists because published NUT vectors cannot tell us whether our ecash
is spendable anywhere else. It is finding M10 of the
[NUT compliance audit](../explanations/nut-compliance-audit.md).

## Prerequisites

- A running Docker daemon (Testcontainers starts Postgres and Nutshell).
- Network access on the first run, to pull `cashubtc/nutshell:0.16.5`.
  Pre-pull it if the build host is offline:

  ```bash
  docker pull cashubtc/nutshell:0.16.5
  ```

No Lightning node is needed. Mint quotes are served by the dummy Lightning
adapter and the melt leg is answered by a scripted payment port.

## Run it

```bash
./mvnw -pl cashu-mint-rest-it -Pintegration-tests -Dtest=NutshellInteropIT test
```

The test is not part of `mvn verify`: integration tests are skipped unless the
`integration-tests` profile is active, as with every other IT in this module.

## Read the result

Two cases run:

- `nutshellCompletesMintSwapAndMeltWithItsOwnOutputSplit` — Nutshell picks its
  own mint output denominations, exactly as it would against any mint. This is
  the honest measure of interoperability.
- `nutshellCompletesMintSwapAndMeltWithACanonicalOutputSplit` — the outputs are
  forced to the minimal split, so the swap and melt legs are exercised even
  while the mint rejects other splits.

A failure message names the stage the wallet reached, the wallet's own error,
and the error body our mint returned, for example:

```
Reached stage 'swap'. Wallet error: HTTPStatusError: Server error '500' ...
Mint said: {"code":"verify_proof_failed_error","message":"Proof verification failed"}
```

That triple is the diagnostic: the stage says which NUT broke, and the mint's
error code says why.

## Why it never skips quietly

Without a Docker daemon the test fails with an explanatory message rather than
skipping. A skip would render the only instrument we have for interoperability
into a green tick that measures nothing, which is the failure mode the audit
filed M10 to end.

## Known failures

Both cases fail today, and that is the expected state until the audit's later
milestones land. See the audit's
[implementation plan](../explanations/nut-compliance-audit.md#implementation-plan)
for the sequencing.

| Stage | Mint error | Related finding |
| --- | --- | --- |
| `mint` | `invalid_denominations` | The mint requires one canonical minimal split of the quote amount; wallets choose their own denominations. |
| `swap` | `verify_proof_failed_error` | Consistent with L1, the `hash_to_curve` secret encoding: Nutshell's proofs verify for Nutshell but not for us. |

The mint leg succeeding under the canonical split is itself a result: Nutshell
accepted and unblinded our blind signatures, so the BDHKE signing path
interoperates.
