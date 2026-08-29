# SIG_ALL compatibility check for deployed proofs

Fixing NUT-11 `SIG_ALL` correctly (issue #383) changes the message that is signed.
Any deployed proof relying on the previous non-standard behaviour therefore stops
verifying. This document records what was actually inspected before that change
was allowed to close, so the conclusion can be re-checked rather than retold.

## The question

The compliance audit stated that Dalia phase 9 escrow proofs use `SIG_INPUTS` and
are therefore unaffected, but explicitly flagged that as needing verification
rather than assumption. The question is narrow and factual: **does any deployed
proof, anywhere, carry `sigflag: SIG_ALL`?**

## What was inspected

Every sibling repository on the build host was searched for `SIG_ALL` in source,
tests, fixtures, and configuration.

| Repository | `SIG_ALL` present | Verdict |
| --- | --- | --- |
| `dalia` | Only in prose (spec notes and a checklist review) | Unaffected |
| `cashu-mint` | Implementation and tests only | Not a proof source |
| `cashu-lib` | The `SignatureFlag` enum and its tests | Not a proof source |
| `imani-apps` | One test asserting a parser reads the tag | Unaffected |
| `imani-wallet.old` | Archived voucher adapter constructs `SIG_ALL` | See below |
| All other repositories | Absent | Unaffected |

## Dalia specifically

Dalia has exactly one place where a signature flag is set, and it sets
`SIG_INPUTS`:

```java
// dalia-arbiter/src/main/java/dalia/arbiter/EscrowSpec.java:58
secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
```

There is no other `setSigFlag` call in the repository, and no occurrence of
`SIG_ALL` in any Java source, fixture, migration, or configuration file. The
audit's claim is confirmed rather than assumed: **Dalia phase 9 escrow is
unaffected by this change.**

That `SIG_INPUTS` is untouched is asserted directly by
`SigAllTransactionBindingTest.sigInputsProof_isUnaffectedByTheTransactionShape`,
which verifies one escrow-shaped proof against four different transaction
shapes — two swaps and two melts naming different quotes — and requires all four
to pass. A `SIG_INPUTS` proof must not notice the transaction it sits in.

## The one construction site, and why it does not block

`imani-wallet.old` contains a voucher adapter that builds a `SIG_ALL` secret:

```kotlin
// imani-voucher/src/jsMain/kotlin/.../WebVoucherAdapter.kt:464
"tags" to listOf(listOf("sigflag", "SIG_ALL")),
```

This does not block the change, for three independent reasons:

1. The repository is the archived predecessor of `imani-wallet` (last commit
   2025-11-24; the live repository has moved on by nine months).
2. The live `imani-wallet` contains no `SIG_ALL` construction at all.
3. The adapter is `jsMain` voucher code, and vouchers are rejected at the mint on
   both the swap and melt paths under Model B enforcement, so such a proof could
   not have been spent here regardless of its signature flag.

`imani-apps` merely *parses* the tag in one test; its production path pins
`sigflag` to `SIG_INPUTS` by type (`sigflag?: 'SIG_INPUTS'`).

## Conclusion

No deployed proof relies on the previous non-standard `SIG_ALL` behaviour. The
compatibility risk the audit raised is real in principle and empty in practice.

## Reproducing this check

```bash
for d in ~/IdeaProjects/*/; do
  n=$(grep -rl "SIG_ALL" --include=*.java --include=*.kt --include=*.ts "$d" 2>/dev/null \
      | grep -v target | grep -v node_modules | wc -l)
  echo "$n  $d"
done
```

Re-run it before any future change to the signed message.
