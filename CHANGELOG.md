# Changelog

All notable changes to the Cashu Mint will be documented in this file.

## [Unreleased]

## [0.38.12] - 2026-09-25

One keyset snapshot per swap request (#476), plus the melt saga timeline append fix (#478).

**The keyset change removes no measured round trips, and this entry originally claimed otherwise.**
It was held back from 0.38.11 for having no measured gain. Before merging, the repeat it targets was
re-measured on 0.38.11 at 19.5 redundant fetches per swap of the same keyset, which read as
justification. Measured again after deploying 0.38.12: **21.5, 21.5, 19.5 per swap across three
isolated runs, indistinguishable from the 19.5 baseline.**

So the fan-out is real and is somewhere this change does not reach, exactly as happened twice before
in #473. `KeySetDirectory` shares a snapshot across the tasks of a request; the repeated
`GET /vault/keyset/id/...` evidently originates below that, inside a single directory load.

What this release does deliver is the design change on its own terms: keysets resolved once per swap
rather than once per input and twice per output, one snapshot shared across the tasks of a request so
fee pricing cannot read a second one, and `MintKeySetResolver` deleted as the duplicate it became. The
snapshot lifetime is the `doExecute` stack frame, so a keyset rotation is invisible for at most one
request.

No latency claim either. Removing roughly 51 round trips per swap in 0.38.11 moved swap p99 by 3ms.

### Fixed

- **A concurrent melt saga timeline append could be silently lost.** `recordTransition` derived its
  sequence number with a read of `MAX(seq)` followed by an insert, and `(melt_saga_id, seq)` is the
  composite primary key, so two writers that read the same maximum built the same key and one insert
  was rejected. Reachable in production rather than only under test: a melt in flight appends from the
  request thread while `MeltSagaReconciler` appends from its scheduled tick, and 16 call sites write
  transitions.

  The consequence is worse than a missing log line. `sweepStaleProofsHeld` deliberately records the
  transition **before** refunding, because a refund with no audit row cannot be reconstructed (#464).
  A dropped append defeats exactly that ordering, leaving proofs returned to a wallet with nothing
  saying why.

  Each attempt now runs in its own transaction (`REQUIRES_NEW` through the bean's own proxy, since a
  constraint violation marks a transaction rollback-only and an in-place retry could never commit) and
  re-derives the sequence. Retrying is safe because the timeline is append-only and the row carries no
  identity of its own. An append that never wins throws rather than reporting success, so a caller
  about to move money is never told a record exists when it does not. See #478.
## [0.38.11] - 2026-09-25

Picks up the fix for the swap half of #473, which lives in `cashu-vault` rather than here.

`imani-bom` 0.1.98 -> 0.1.111, bringing `cashu-vault` 0.12.5 -> 0.13.0. Loading a keyset now costs one
call rather than one per key: the batch endpoint carries the derived public key, so `DBKeySetVault`
no longer fetches a private key per key only to derive the public one and discard it
(cashu-vault#146).

**This is the release that should finally move swap cost, and that is not yet proven.** Two attempts
from this side (0.38.8 and 0.38.9) both passed their unit tests and measured as no change on staging,
because the round trips were never in this repo. #473 stays open until a re-measure with the anchored
harness says otherwise, and no performance number is claimed here.

Operationally: `cashu-vault` 0.13.0 carries a schema migration (`V12`), so this is a coordinated
deploy with the vault service rather than a drop-in mint upgrade.

## [0.38.10] - 2026-09-25

Performance release for `POST /v1/checkstate`, which was dominated by redundant sequential HTTP round
trips to the vault rather than by cryptography. See #473 for the profiling.

Confirmed on staging after deploy: 1.0 vault GET per proof at n=1, 5, 20 and 50, over 16 consecutive
measurements with no anomalies.

The swap half of #473 is deliberately NOT in this release. Two attempts at it
(`KeySetDirectory`, then sharing one directory per request) both passed their unit tests and both
measured as no change on staging, because the round trips happen one layer below what those changes
control: `DBKeySetVault` issues one HTTP call per key inside a single generation load. That is
cashu-vault#146 and #473 stays open for it.

### Fixed

- **`/v1/checkstate` ran two `CheckStateTask`s per mint, not one.** `CrossMintCheckStateMerger`
  unions `mintLoadService.load(false)` and `load(true)` so that proofs on retired keysets still report
  a state, but `DBMintVault.load(boolean)` ignores its `archived` argument and answers every mint
  either way (cashu-vault#145), so the two calls returned the same mints and each was asked twice.
  `allMints()` now de-duplicates by mint id.

  De-duplicating by id rather than by object is deliberate: the two loads populate the same mint with
  different keysets, so the instances are not equal and neither is a superset of the other. It is safe
  only because the merge reads nothing but `mint.getId()`.

  Fixing the vault to honour `archived` would **not** replace this and must not be attempted as
  stated in cashu-vault#145: staging carries three archived keysets hanging off non-archived mints, so
  filtering mints by their own flag makes archived keysets unreachable and breaks NUT-02 redemption.

- **`POST /v1/checkstate` read the vault six times per proof for the same key.** The endpoint is
  served by `CrossMintCheckStateMerger`, which runs one `CheckStateTask` per mint the deployment
  serves, and each task looks every requested `Y` up in the vault. The vault lookup behind
  `retrieveProofByY` is keyed on the curve point alone with no mint identifier, so all six mints
  asked the vault the identical question and received the identical answer. Measured on staging
  with 20 genuinely distinct proofs: 120 vault GETs over 20 distinct keys, 6.0 GETs per `Y`, at
  ~4ms per GET and 18-26ms per proof end to end. The round trips were the cost.

  `merge(...)` now wraps the vault service in a `RequestScopedProofLookupCache` for the duration of
  one request, so each distinct `Y` is read once: a measured 6x reduction, 120 GETs to 20. Misses
  are cached alongside hits, because the common case is a proof the vault does not hold, where all
  six lookups returned nothing.

  The cache is deliberately not shared across requests. A proof moves
  `UNSPENT -> PENDING -> SPENT`, so a cached `UNSPENT` outliving its request would report a spent
  proof as spendable, turning a latency fix into a double-spend window. Its lifetime is the
  `merge(...)` stack frame, with no static field, Spring scope, or `ThreadLocal` to leak. The
  mutating swap and melt paths (`InvalidateProofsTask`, `SwapProofHold`) receive the vault bean
  directly and cannot reach it, and the wrapper discards its snapshot on any write so it stays
  substitutable for the service it decorates.

  Lookup de-duplication within a single request falls out of the same change: 20 identical `Ys`
  now cost one GET rather than 120. The response is still built per requested entry, because
  NUT-07 requires one state per requested `Y` in request order, so a client sending a `Y` twice
  still receives two entries.

- **Request latency histogram ceiling raised from 10s to 30s.** The top finite bucket of
  `cashu_mint_requests_duration_seconds` was 10s while `/v1/checkstate` routinely exceeded it:
  measured on staging, `le=10.0` held 14 of 24 observations and `+Inf` held all 24, so 10 requests
  sat above the highest finite bucket. `histogram_quantile` cannot interpolate past that boundary,
  so p99 read exactly 10.00s and `CashuMintLatencySLOBreach` understated every breach it fired on.
  Alert and dashboard expressions are unchanged. The new `le="30.0"` bucket series is additive.
> **Measured as no change on staging.** Both entries below passed their unit tests and neither
> reduced swap vault cost when deployed. The round trips happen one layer below what these changes
> control: `DBKeySetVault` issues one HTTP call per key inside a single generation load, so sharing
> a directory cannot collapse calls that occur inside one load it already counts as one. Tracked as
> cashu-vault#146. These are kept as correct design changes, not as performance fixes.

- **Keyset loads were collapsed per task, not per request.** 0.38.8 added `KeySetDirectory` so each
  generation is read at most once per directory, and its unit test showed 5/10/15 loads collapsing
  to 2. Staging still measured 22.5 keyset loads and 87 vault key GETs per swap over 8 real swaps,
  because every task that needed keysets built a directory of its own: `SwapTask` for the
  validation rules, a separate `MintKeySetResolver` behind `VerifyFeesTask` for NUT-02 fee pricing,
  and the same pair again on the melt path plus one `MintLoadService.keySet(id)` per input for the
  IOU check, which is itself `keySets(false) + keySets(true)`.

  `SwapTask.doExecute` and `MeltTask.doExecute` now each build one `KeySetDirectory` and pass it
  explicitly to every task and check that needs keysets, following the `RequestScopedProofLookupCache`
  pattern that `CrossMintCheckStateMerger.merge` uses. `KeySetDirectory` also implements the NUT-02
  `KeySetResolver`, so fee arithmetic reads the same snapshot instead of building a second one, and
  `MintKeySetResolver` is removed as the redundant duplicate it became.

  The lifetime boundary is the `doExecute` stack frame, entered once per HTTP request, with no
  static field, Spring scope or `ThreadLocal`, so a keyset rotation is invisible for at most one
  request. Within one request sharing is also what correctness wants: the unit rules, the
  archived-keyset rule, the IOU check and the fee arithmetic must judge every input and output
  against one consistent set of keysets.

  Measured in tests with `VerifyFeesTask` left running rather than stubbed: 3 generation loads per
  swap before, 2 after, independent of swap size. Private key material is deliberately untouched;
  `DBKeySetVault.load` still performs one HashiCorp read per key, because extending the in-memory
  lifetime of secrets is a security decision outside the scope of a load-count fix.

- **`POST /v1/swap` resolved the mint's keysets once per input and per output instead of once
  per request.** `ValidateTransactionTask` asks the keyset directory one question per input and
  two per output, and every question was a full `MintLoadService` load. A swap of n inputs and
  n outputs therefore cost 5n loads, reproduced as 5, 10 and 15 loads for n = 1, 2 and 3. Each
  load is O(keys) HTTP calls to the vault, because `DBKeySetVault` resolves each key's private
  material individually, which is how one swap reached ~98 vault key GETs with the same keyset
  refetched 5-8 times and p99 latency of 6.29s that did not track signature count.

  `KeySetDirectory.of(MintLoadService)` now reads the active and archived generations at most
  once each and answers from memory, so a swap costs at most 2 loads regardless of size. The
  IOU rejection in `SwapTask` indexes the IOU keyset ids once rather than rescanning the mint's
  keysets per item. Rejection behaviour is unchanged: IOU inputs and IOU outputs are still
  refused with `iou_not_swappable`.

  The mechanism was not the originally suspected one. `Mint.getKeySets()` is a plain field
  accessor and performs no I/O; the repeated loads came from the directory behind
  `ValidateTransactionTask`.

## [0.38.4] - 2026-09-22

### Security

- **BouncyCastle 1.84 -> 1.85 for CVE-2026-8763 (CRITICAL)**, via `imani-bom` 0.1.98. X.509
  Name Constraints can be bypassed with a trailing dot in an `rfc822Name` or URI, so a
  certificate can assert a name the constraint exists to forbid.

  Found by this repository's own SBOM-based dependency scan (#432) — the first CRITICAL it has
  caught, and the argument for having rebuilt it. The file-based scan it replaced reported
  zero findings against the same tree, because in a multi-module Maven build the coordinate
  and its version never appear in the same file.

  The scan had been failing on every run for days before anyone read what it said, which is
  the same failure one layer up: a control that works, reports truthfully, and is not read.

- **Secret scanning added.** This was the only repository in the ecosystem without it (#438),
  and the shared template's own header cites a cashu-mint test fixture as its example finding.

  A bare scan reports 178 findings over 1689 commits; none is a credential. 105 are keyset
  ids, pubkeys and proof commitments — 64-hex by construction and public by design — and 72
  are `local-dev-token` in the admin tutorials. The shared `.gitleaks.toml` takes that to 73,
  and two exact-value entries take it to zero.

  Allowlisting is by **value shape, never by path**: exempting a directory exempts every rule
  in it, so a real token pasted into a tutorial would go unreported. Verified by planting a
  GitHub PAT, an AWS key and a Stripe live key in a test file — all three are still caught.

### Added

- **A test binding the four copies of the denomination ladder together.** The ladder is
  written out in `VaultProvisioningOutboxHandler`, `MintPreloadDataGenerator`,
  `keyset.properties` and `tools/provision-mint`, and `DenominationLadderTest` only ever
  checked one of them.

  Truncating the provisioning copy to `2^20` and running the entire suite produced **no
  failure**, while a mint provisioned through that path would silently be unable to sign
  above ~1M: the exact defect 0.38.0 widened the ladder to fix, reintroduced through the one
  door nobody was watching.

### Changed

- `imani-bom` 0.1.87 -> 0.1.98.
- CI forces a dependency re-check (`-U`). `actions/setup-java`'s cache restores `~/.m2`
  including the `*.lastUpdated` markers Maven writes when a lookup *fails*, so a run that
  raced an internal release cached that failure for 24 hours and never retried — turning an
  ordinary publishing race into a day of red builds that re-running could not fix.

### Notes for operators

- **This release cannot build on a clean runner until cashu-vault publishes.** `imani-bom`
  names `cashu-vault` 0.12.5, and that repository has published nothing since 0.12.1
  (cashu-vault#144). Local builds succeed only because the artifact is installed in `~/.m2`
  by hand, which is precisely the masking that issue describes.

- `cashu-vault.version` in this pom is the **docker image tag** for the opt-in admin e2e
  suite, not a library version. It is knowingly stale: the newest published image is 0.9.1,
  so every value since names a tag that does not exist. Left at 0.12.1 rather than advanced,
  because moving a pointer to a second nonexistent tag would imply it had been checked.

## [Unreleased]

## [0.38.7] - 2026-09-24

### Fixed

- **An expected answer was counted as a task failure, so healthy issuance fired
  `CashuMintTaskFailureRate`.** On a staging run where every sale succeeded, `MintQuoteStatusTask`
  recorded 11 failures against 10 successes with no errors logged anywhere. All 11 were
  `InvoiceNotPaidException`, which is the correct answer to "has this invoice been paid yet"
  when the answer is not yet. Every client polls until it flips, so each healthy sale produces
  several by design and the ratio sits near 50% in normal operation.

  Expected outcomes now go to `cashu_mint_task_expected_outcome_total` instead. Counted rather
  than discarded, because how often clients poll early is useful; it is simply not a failure.


## [0.38.6] - 2026-09-24

### Changed

- **The issuance identity header is `X-Mint-Issuer-Identity`, was `X-Dalia-Identity`.** This
  filter was built for Dalia Phase 9 and the name came with it, but the mint is not a Dalia
  component: Dalia's SDK sends that header to a Dalia engine, Dalia is not deployed alongside
  this mint, and no Imani gateway sent it either. No compatibility alias, because keeping one
  for a caller that does not exist preserves the confusion the rename removes. Anything that
  needs the old name sets `cashu.mint.issuance.rate-limit.identity-header`.

- **The daily issuance quota is 2000, was 60.** 60 was six minutes of the 10/min burst beside
  it, so the pair could not describe one intended load. And with no caller sending an identity
  header the bucket keyed on remote address alone, so those sixty were shared by every merchant
  behind a gateway: ten stalls selling twenty coupons each need 200 and were refused before
  lunch. Measured on staging at 48 of 60 consumed by test traffic, with 24 breaches recorded.

  The per-minute burst remains the control doing the real work. This one bounds a runaway that
  never trips it.


## [0.38.5] - 2026-09-24

### Fixed

- **A voucher under 10 sat was invoiced for nothing.** The fee is
  `floor(faceValue * feePercentage / 100)`, so any face value below 10 at the default 10%
  charged ZERO. The mint created a zero-amount invoice, which settles trivially, and then
  refused its own webhook with `amount_mismatch` because a payment of 0 cannot match. Nine
  quotes were stranded this way and the retry loop behind them produced 9,962 refusals
  overnight, growing to 12,358 by the time this shipped, against 40 accepted.

  `VOUCHER_QUOTE_FEE_MIN_SAT` (default 1) now floors the charge, so an invoice is always
  payable. A floor ABOVE the face value would charge more than the voucher is worth, which
  is always a misconfiguration: warned rather than refused, because refusing takes out
  issuance instead of the bad setting.

- **`invalid_amount` is split from `amount_mismatch`.** A webhook whose amount is structurally
  impossible is a different fault from one that simply disagrees with the quote, and reporting
  both as a mismatch sent the investigation the wrong way.

### Changed

- The point at which a voucher quote becomes irreversible is now marked in the code.
  `createMintQuote` runs BEFORE `persistVoucherQuote`, so a failure between them strands a
  payable invoice the mint will not honour. The comment names the boundary; the ordering is
  unchanged in this release.



## [0.38.3] - 2026-09-22

### Fixed

- **A melt reconciler pass that died part-way through was logged as routine noise (#464).**
  Both passes in `reconcileTick` caught `RuntimeException` and logged `warn` with
  `getMessage()`. Two things were wrong with that line.

  It was the **wrong level**. A pass throws only after `casState` has already moved some
  saga out of the state that pass selects on, so no later tick can find it to retry — the
  exception marks a saga now terminal with its proofs possibly unsettled, which is a
  customer's money frozen with no process that will free it. At `warn`, indistinguishable
  from "there was nothing to sweep". Five such failures ran for three weeks unnoticed.

  It also **dropped the stack trace**. A `NullPointerException` has a null message, so the
  most common runtime failure rendered as `cause=null` — the least informative line for the
  failure hardest to diagnose. The same bug had already been fixed in `settleProofs`'s
  catch for the same reason.

  Now `error`, alert-tagged, with the throwable attached and a note that no later tick will
  retry. Deliberately no new counter: `melt_saga_terminal_unsettled` already counts the
  consequence, and a second metric for the cause would fire in lockstep and add no decision.

### Notes

- The ordering halves of #464 were already in place: both `sweepStaleProofsHeld` and
  `resolvePaymentUnknown` record the transition **before** moving the money, relying on
  `refundForHold` / `commitSpentForHold` being idempotent conditional updates. An audit row
  written for work that did not complete is recoverable; money moved with no record of it
  is not.

## [0.38.2] - 2026-09-22

### Fixed

- **The `cashu-mint-admin` parent pom was never published (#359).** The root reactor
  listed the four `mint-admin-*` modules directly and never the aggregator they all
  declare as their parent. It was therefore never built and never deployed — 404 at
  every version, while the modules themselves published perfectly well.

  The effect is invisible from inside this build, which resolves the parent from the
  working tree, and fatal from outside it: anyone resolving `mint-admin-core` and
  friends fails on a parent that does not exist in any repository.

  The root now lists `cashu-mint-admin` alone. The aggregator already declares all four
  children, so naming them here as well duplicates them in the reactor and Maven refuses
  to start the build — which is why the obvious fix of *adding* the aggregator beside
  them does not work.

## [0.38.1] - 2026-09-22

### Fixed

- **`VaultPreloadSeeder` treated any vault error as "keyset absent".** `keySetExists`
  caught `Exception` and returned `false`, so a transport or validation failure became
  a decision to seed a keyset that already existed. Seeding then failed inside
  `store()`, which performs the same lookup.

  Observed against a vault that rejected NUT-02 v2 keyset ids: the mint logged
  `Failed to seed the vault from preload JSON` for a keyset it was serving correctly at
  that moment, which is about as misleading as a log line gets. Worse, the seeding
  attempt POSTed the key set back, and a bug on the vault side (fixed in cashu-vault
  0.12.3) turned that into a deletion of every key the keyset had.

  Only a 404 now counts as absence — a real "not there". Anything else means the
  question could not be answered, and answering "missing" is the one response
  guaranteed to cause damage, so it propagates and is logged as the failure it is.

### Notes for operators

- Pair this with cashu-vault >= 0.12.3. This change stops the mint from *asking* to
  re-store a keyset it should not; 0.12.3 stops that request destroying keys if it is
  ever made again.

## [0.38.0] - 2026-09-22

### Changed

- **The denomination ladder reaches the mint's own per-operation cap: 1..2^23 instead of
  1..1024.** A ladder that stops below the amounts being signed is not a smaller ladder, it is a
  different cost curve. While the ladder reaches the amount, a greedy largest-first split uses
  each denomination at most once and proof count is the amount's POPCOUNT. Once the amount
  outgrows it, the top denomination repeats and proof count scales **linearly** with magnitude,
  unbounded.

  That second regime is what shipped. On 1..1024 a EUR 25.00 sale (33,246 sat) was 39 proofs
  rather than 8, and a EUR 1000 sale was 1,302 — which is how a token-size ceiling came to refuse
  ordinary trade at about EUR 18 a sale (398ja/imani-gateway-portal#43).

  2^23 = 8,388,608 is the largest power of two at or below the 10,000,000 sat cap staging sets
  (imani-deploy#59). Every amount the mint will sign is now at most 24 proofs, flat across the
  range rather than degrading at the top.

  **Minor rather than patch: this changes what a new keyset contains.** The admin defaults, the
  preload `keyset.properties` and the provisioning tool move together — the admin default stopped
  at 128, lower still, so a mint provisioned through it was born with this bug.

### Operators must act

- **Existing mints need a key rotation to get the new ladder.** Denominations are read fresh at
  rotation (the same path a fee change takes, ADR-0009), so updating the mint's configured
  denominations and rotating is the whole operation. **Nothing is destroyed:** rotation archives
  the superseded keyset and reinstates it if provisioning fails, and the mint serves archived
  keysets alongside the active one — so coupons already issued keep redeeming at their original
  keyset.

## [0.37.2] - 2026-09-22

### Fixed

- **`/v1/keys` no longer fans out hundreds of vault calls** (#467). Each uncached read is the
  O(keys) walk that once killed `imani-vault-jpa` with an `OutOfMemoryError`, taking its
  `http-nio` acceptor thread with it — so the vault refused all connections and nothing could
  mint.

  A cache alone made that rarer rather than impossible, and left it aimed at the worst moments:
  every restart begins cold, and a deploy is exactly when every wallet reconnects at once.
  Measured at 20 concurrent requests against a cold cache producing 20 full vault loads.

  A per-generation lock with a double-check means one loader per generation while the rest wait
  and then read the cache. Active and archived lock separately, so a slow archived read cannot
  stall wallets asking for active keys.

- **The same fan-out on ARCHIVED keysets**, which the first fix missed. On any mint that has
  rotated its keys the original bug was still fully present for archived reads.

**Operators need not act.** No configuration, schema or wire change; this is the same responses
served with far fewer vault reads.

### Fixed (release machinery)

- **The `cashu-mint-admin` modules are versioned with the rest of the reactor again.** Their
  parent and their own `<version>` were left at 0.37.0 when 0.37.1 was cut, along with the root
  `<cashu-mint.version>` property whose own comment says to keep it in step on every release.

  The release workflow has therefore failed on every tag since v0.37.0, which is why
  `mint-admin-core`, `mint-admin-rest` and `mint-admin-web` have never been published at any
  0.37.x coordinate. A local `mvn install` hides this completely: the parent resolves from the
  working tree, so the reactor builds while CI cannot read the projects at all.

## [0.37.1] - 2026-09-21

### Changed

- **Adopts imani-bom 0.1.87** for cashu-lib 0.30.4 and cashu-voucher 0.14.3.

  The previous pin (0.1.86) named cashu-lib 0.30.3, a tag that contains no warrant code, while the
  code that did contain it claimed to be the already-published 0.30.2. The mint does not read
  warrants, so nothing here behaved differently, but it resolved a coordinate whose published bytes
  did not match the source it was cut from. This moves it onto fresh coordinates per ADR 0006,
  which keeps cashu-lib, cashu-voucher and cashu-mint on one consistent set.


## [0.37.0] - 2026-09-21

### Fixed

- **The mint could not interoperate with any spec-conformant wallet.** Three defects, each hidden
  behind the one in front, found by driving a current Nutshell wallet against a live mint rather
  than against the test harness. `GET /v1/keys` omitted the NUT-01 `active` field; the melt quote
  returned null for the required `unit` and `request`; and `POST /v1/melt` returned the pre-NUT-23
  `{paid, payment_preimage}` shape instead of the quote object. The last is the worst: the melt
  completes at the mint, so a wallet that cannot parse the answer sees something indistinguishable
  from a failure, and a wallet that retries has already had its inputs spent. `NutshellInteropIT`
  now runs against a current wallet and completes the full mint → swap → melt flow (#465, #466).

- **The melt reconciler settled proofs before recording the transition, outside any transaction.**
  On all three terminal paths. A failure in between left the money moved with no record of why —
  and the CAS had already taken the saga out of the state the sweep selects on, so nothing would
  retry. The `COMPLETED` path was sharpest: it burns the customer's inputs *after* the invoice is
  paid. Both vault operations are idempotent conditional updates, so recording first is safe and
  recoverable; the reverse is not (#464).

- **A settle that failed was invisible.** It was logged and skipped, with nothing retrying, so a
  customer's proofs sat `PENDING` with no signal. `cashu_mint_melt_terminal_unsettled` now counts
  terminal sagas with no settlement recorded, alerting at critical with a pager route — unlike the
  voucher gauges, this invariant has no reconciler behind it by construction (#464).

- **A customer could pay for a voucher and never receive it.** A 1000 EUR sale auto-split into
  five 200 EUR parts delivered 200: all five invoices settled and all five webhooks were accepted,
  but only two vouchers were issued. Recording the payment and creating the funding row that backs
  it were never causally linked — `QuoteStatusUpdater` deliberately stopped at the `accepted`
  `webhook_event`, and the funding row was created lazily by `VoucherFundingResolverImpl`, which is
  reached only from an inbound client mint request. Sequential sales hid this by giving each part
  its own polling window; five payments inside one window exhausted the client's 60s budget after
  two, and the rest stayed `UNFUNDED` with the money taken. 62 such rows (341610 sat) were
  stranded on staging, the oldest since 2026-08-29. The webhook now resolves and attaches the
  funding row in the same transaction as the payment it justifies, so the two commit together or
  not at all (#459).
- **`VoucherFundingReconciler` sweeps for anything that bypasses that path** — a bug, a rollback, a
  manual edit, or a future payment provider wired straight to the event table. Quotes left
  `UNFUNDED` with an accepted payment older than `cashu.mint.voucher.funding-grace-period` (2m) are
  driven through the same resolver the request path uses, so the two cannot diverge in what counts
  as funded. The existing stranded rows heal on the first sweep. Note that the sweep restores
  `FUNDED`, not `ISSUED`: signing needs the client's blinded outputs, which the mint never
  persists, so the value ends up durably backed and waiting for a returning client rather than
  issued unilaterally.

### Added

- **`ReconcilerCoverageArchTest`** — every non-terminal state of a value-bearing machine must be
  swept on a schedule or carry a written exemption. "No correct resolution exists" is a
  first-class reason, with `MintQuote.LifecycleState.PAID` as the worked example: issuance needs
  blinded outputs the mint never persists, so a sweep there would bar a customer from money
  already taken. The rule is verified against the bug it exists to catch (#461).

- **CI runs the integration suite and asserts it ran.** `cashu-mint-rest-it` executes its ITs
  through surefire while `skip.integration-tests` defaults to true, so `mvn verify` printed
  `BUILD SUCCESS` over a suite that never executed. Repairing that surfaced nine distinct test
  defects — none in production code, every one a test that had stopped describing the system
  (#463).

- `cashu_mint_quote_paid_unissued` gauge (ADR 0002, DB-derived): mint quotes in `PAID` past
  `cashu.mint.quote.paid-unissued-ttl` (1h). The customer's payment settled and was accepted, but
  `PAID → ISSUED` runs only on an inbound client mint request, so a client that never returns
  leaves the money taken and nothing issued. Two such quotes sat unnoticed on staging for three
  weeks (#460).

  **This is the one money-at-risk invariant with no reconciler behind it, and that is deliberate.**
  Issuing needs the client's blinded outputs, which the mint never persists, so no background job
  can complete the transition. Nor is the quote expired to tidy the count: issuance CAS-transitions
  from `PAID`, so expiring it would permanently bar the customer from money already taken —
  `MintTask.alreadyPaid` already records why, namely that an expiry bounds how long the payer has
  to pay, not how long the mint will honour a payment it has taken. The row stays claimable and
  the gauge is the whole mechanism.
- `cashu_mint_voucher_paid_unfunded` gauge (ADR 0002, DB-derived): voucher quotes still `UNFUNDED`
  despite an accepted payment. Non-zero means the mint has taken money it has not issued against;
  there is no benign instance of it.
- `cashu_mint_voucher_funding_reconciled_total{outcome="recovered|failed"}`: the safety net's own
  health. A steady `recovered` rate means the webhook-side attach is leaking and should be
  investigated rather than celebrated.

## [0.36.5] - 2026-09-16

### Fixed

- **Every Prometheus scrape of the mint was a 401, so the staging Grafana dashboards showed
  nothing.** `ManagementSecurityConfig` (0.36.0, audit H-3) put `/actuator/prometheus` behind
  the operator credential, but the shipped `prometheus.yml` never presented one, so the target
  went `down` with `server returned HTTP status 401 Unauthorized` and every panel read "No data".
  The `cashu-mint` scrape job now sends basic auth from a `password_file` that the observability
  compose file mounts as a secret, chosen through `CASHU_MINT_SCRAPE_PASSWORD_FILE`. The dev stack
  gains a default bcrypt `MINT_ADMIN_PASSWORD` whose plain text matches the tracked dev scrape
  file, so `docker compose up` on both stacks carries data again with no extra setup. The staging
  install guide now covers the credential and the observability stack, and `.env.example` warns
  that a bcrypt hash needs every `$` doubled or compose silently truncates it.
- `scripts/mint-admin-password.sh` generates the mint's operator credential in both forms it is
  consumed in: the compose-escaped bcrypt line for `MINT_ADMIN_PASSWORD` and the plain-text file
  Prometheus scrapes with, from one random password so the two cannot drift.
- `PrometheusScrapeConfigTest` now fails if the scrape job loses its `basic_auth` block, so the dashboards
  cannot go blank the same way again.

## [0.36.4] - 2026-09-15

### Fixed

- **The whole platform shared one issuance rate-limit bucket, so merchant voucher issuance died
  on 429.** A reverse proxy fronts the mint and forwards to it, so every request arrived from the
  proxy's address and keyed to one identity. Measured on staging: two different services and an
  external internet caller all recorded as `identity=ip:172.19.0.1`. `perMinuteBurst=10` was
  therefore the budget for every merchant, every wallet, and any stranger who found the mint —
  and any caller could exhaust it for everyone.

  The caller address now comes from `X-Real-IP` (falling back to the first `X-Forwarded-For`
  entry), but **only from a peer on the existing `trusted-proxies` allowlist**. From any other
  peer the headers are ignored, so this is not a limit bypass — the same trust boundary the
  identity header has used since M-2. With the allowlist empty, behaviour is unchanged.

- **NUT-04 quote status polling no longer spends the issuance budget.** The filter covered all of
  `/v1/mint`, including the read-only `GET /v1/mint/quote/{method}/{quote_id}` that a wallet polls
  every couple of seconds until the quote reports PAID. One voucher therefore exhausted a burst of
  10 while waiting for its own invoice: 15 of 19 rejections on staging were reads against a single
  quote id, and the wallet's circuit breaker opened on top. Writes under `/v1/mint` are limited
  exactly as before.

### Security
- `/v1/restore` and `/v1/checkstate` now enforce their declared request-size limits.
  `PostRestoreRequest.MAX_OUTPUTS` and `PostCheckStateRequest.MAX_SECRETS` were declared as
  Bean Validation `@Size` constraints and never applied, because the controllers bound the
  body without `@Valid`. Both endpoints are unauthenticated and both perform one vault lookup
  per element — and `checkstate` repeats that per mint, archived ones included — so a single
  2 MiB body turned into tens of thousands of vault calls, amplifying into the component every
  value-moving path depends on. The limits are additionally enforced inside
  `RestoreSignaturesTask` and `CheckStateTask` so they hold for every caller rather than only
  the HTTP path, and `MethodArgumentNotValidException` is mapped to the protocol's error
  shape. Every regression test was confirmed to fail without the enforcement.
- `CashuControllerRequestLimitTest` covers the HTTP contract with a supplied validator,
  including that a request *at* the limit still succeeds. `RequestSizeLimitIT` covers the same
  through the real application context, which is the only one of the two that speaks to the
  deployed wiring. Removing `@Valid` fails both.
- `cashu-mint-rest` now declares `spring-boot-starter-validation` explicitly. It is not what
  fixed the vulnerability: a validator already reached the module transitively through
  `cashu-mint-jpa`, which was confirmed by removing the dependency and observing the tests
  still pass. It is declared because a runtime-scoped persistence module is not where a
  web-layer validator should come from — if that module drops it, constraints on two
  unauthenticated endpoints go silent with nothing failing to compile.

### Added
- `SigCountTest` covers the NUT-11 rule that a signature threshold counts distinct public
  keys rather than signatures. The rule had no test: deleting the deduplication in
  `SigningKeyCounter` lets one key satisfy a 2-of-2, which is a forged multisig, and nothing
  in the suite objected. The implementation was already correct; only the coverage was missing.
- `docs/explanations/appsec-review-2026-09.md` — an application security review across
  cashu-lib, cashu-mint, cashu-vault, cashu-ledger, cashu-voucher and cashu-wallet, recording
  the findings, the reasoning behind each severity, and what the codebase already gets right.

## [0.36.3] - 2026-09-14

### Added

- **The swap-hold signing-order invariant is pinned by a test.** An AppSec review of the estate
  raised it as an unwritten assumption: the order in which a swap and a hold are signed is
  load-bearing, and nothing failed if it changed. A property nobody has written down is a
  property the next refactor is free to break.

## [0.36.2] - 2026-09-12

### Changed
- Dependencies now resolve through `imani-bom`, advanced to `0.1.71`. That BOM
  carries `imani-wallet-lib 0.1.40`, whose headline change is that the wallet's
  proof encryption is actually switched on: `persistProofsInternal` wrote the
  proof secret in plaintext while encrypting its three neighbours, and nothing
  ever enabled encryption in the first place.

### Fixed
- `cashu-mint.version` and `cashu-mint-admin.version` were pinned at `0.36.0`
  while the reactor built `0.36.1`. The first is real dependency management for
  this project's own modules, so it claimed the release shipped against
  last-version siblings; the reactor overrides it for modules it builds, which
  is why nothing broke, but the two disagreeing is how a stale pin escapes
  notice. The second was referenced by nothing at all. Both now track the
  project version.

### Release note
- `0.36.0` and `0.36.1` were published to Reposilite but **never tagged**, so
  the git history does not record them. This release is tagged. The workflow
  that should have done it could never publish at all — see #409 and #415.

## [0.36.1] - 2026-09-07

### Fixed
- The voucher ledger is now built with an identity it can **sign** with.
  `VoucherConfiguration` passed the public key alone, which leaves
  `issuerIdentity` null, and `NostrVoucherLedgerRepository` then refuses to
  publish — correctly, since an unsigned ledger event is not evidence of
  anything and relays reject it.

  It is worse than a plain bug because of *when* it fails: the repository throws
  on the publish path, not at construction, so the mint boots clean and only
  fails once a voucher exists, on a background thread, while the caller sees
  success. The same defect was live in the customer gateway, where one boot lost
  12 publishes. Here it was masked entirely because `voucher.enabled` is off in
  the test stack — the kind of latent fault that surfaces on the day the feature
  is switched on.

  The private key was already required a few lines below for `VoucherService`,
  so nothing new needs configuring.

### Changed
- A private key that does not derive the configured `voucher.mint.issuerPublicKey`
  is now refused at startup, as is a missing one. Publishing ledger events
  authored by a key nobody verifies against is worse than not publishing,
  because it looks like it worked.

## [0.36.0] - 2026-09-06

Security remediation from the 2026-09-05 audit, plus the defects an adversarial review of that
remediation found. **Contains the audit Critical.** Minor rather than patch: the webhook timestamp
is mandatory outside local profiles and the production compose stack changed shape.

### Security

- **Every swap input is verified to have come from this mint** (audit C-1). P2PK proofs were never
  BDHKE-verified on `/v1/swap`: the check lived in the spending-conditions path and was never
  reached for them, so a forged proof passed with nothing thrown. Now a `ProofAuthenticity` check
  runs over every input before dispatch. The regression test uses real crypto and fails against
  the old code.

- **Webhook signatures are bound to a timestamp** (audit M-5), and the timestamp is now
  **required** outside the local profile. It defaulted false and appeared in no properties file,
  so an attacker replayed any captured delivery for ever by simply omitting the header: the window
  check passes on null and the signature falls back to covering the bare body, which is what the
  old replayable MAC covered. Every replay test supplied a timestamp, so they proved the mechanism
  and said nothing about the default posture.

- **Melt saga aborts on a CAS failure** rather than proceeding.

### Fixed

- **The production stack starts again.** The Vault hardening pointed the listener at TLS
  certificates that do not exist in the repository, with no generation step and a read-only mount,
  so Vault exited immediately and, with `restart: unless-stopped`, crash-looped; `vault-init` gates
  on it, the vault service on that, and the mint on that, so `docker compose up` produced a stack
  that hung. Two further blockers sat behind it: the healthcheck spoke HTTP to an HTTPS listener,
  and used `vault status`, which exits 2 on a sealed Vault, so it could never have passed even
  with certificates present. Verified by running the container.

- **Database credentials are no longer hardcoded.** The same file that says "every credential is an
  environment variable with NO default" set `postgres`/`postgres` for the payment adapter and the
  admin service, which could not have authenticated anyway since both databases are created with
  `POSTGRES_USER` and their own password variable.

- **AppRole credentials use the variable names Spring actually binds.**
  `VAULT_HASHI_ROLE_ID` relax-binds to `vault.hashi.role-id`, a property that does not exist, so
  the credentials were read as absent. `VAULT_HASHI_AUTH_METHOD` was `APPROLE` in capitals, which
  the switch did not match, sending it to token authentication with a null token.

- **`locktime` widened to `long`**, and two off-curve test keys corrected (found by the new
  `cashu-lib` on-curve check).

- **`MerchantVerificationService` receives an issuer key registry**, configurable as
  `cashu.mint.voucher.issuer-keys.<issuerId>`.

### Added

- **`docs/how-to/provision-vault.md`**, the first-boot runbook that `vault.hcl` and the compose
  file both referenced and which did not exist.

### Changed

- **`cashu-lib` 0.30.0, `cashu-vault` 0.12.0, `cashu-wallet` 0.8.0, `cashu-voucher` 0.14.0,
  `cashu-ledger` 0.6.0, `nap-java` 0.6.1 -> 0.8.0.**

## [0.35.0] - 2026-09-02

### Fixed

- **The voucher profile could not start: `commons-lang3` was missing at runtime** (#405).

  `cashu-mint-rest` declared it `<scope>test</scope>`. A direct declaration overrides a
  transitive one, so the jar was stripped from the runtime image even though
  `nostr-java-core` declares it at compile scope and needs it — `HexStringValidator` calls
  `StringUtils`, and the voucher ledger path reaches that validator when it publishes.

  The result was `NoClassDefFoundError: org/apache/commons/lang3/StringUtils` while building
  `voucherLedgerPort`, which takes the whole application context down, so `VoucherController`
  never registered and `POST /v1/vouchers` 404'd.

  Fixed by **removing** the declaration rather than widening its scope: no source in this
  repository imports `commons-lang3`, so declaring it would claim a dependency this module does
  not have and would pin a version for a library it never calls. Verified against the packaged
  jar — absent with the test scope, present without it.

### Documentation

- **The voucher issuer keys are secp256k1/BIP-340, not ED25519.** Four places said ED25519 —
  `application-voucher.yml`, `docs/reference/configuration.md`, `VoucherProperties`, and the
  runtime error message in `VoucherConfiguration`, which is the one an operator actually hits.
  Voucher signatures are BIP-340 Schnorr over secp256k1, the same scheme Nostr uses, and the
  public key is the 32-byte x-only form rather than the 33-byte compressed one. Following the
  old comment sends someone to an ED25519 generator and produces a key the mint rejects.

### Added

- **`P2PK_VOUCHER` proofs are enforced with both spending conditions.** The kind (cashu-lib) is
  a voucher that is also P2PK-locked, so the mint must check the voucher conditions - expiry,
  issuer signature, double-spend, BDHKE - **and** require a witness signature from the key in
  `data`. `P2PKVoucherSpendingCondition` delegates to both existing conditions rather than
  reimplementing either.

  Running only one looks like success, which is the whole reason the kind exists. Voucher
  checks alone leave the lock advisory, so a thief holding the proof can spend it. P2PK checks
  alone honour a forged or expired voucher locked to the attacker's own key.

  Dispatch order is load-bearing: `P2PKVoucherSecret` extends `P2PKSecret` and a `P2PK_VOUCHER`
  is a voucher, so both prior branches in `VerifyProofsTask` would have matched it and run half
  the checks. It is now matched first.

### Fixed

- **`VoucherSpendingCondition` no longer skips its checks for a `P2PK_VOUCHER`.** It cast to
  `VoucherSecret` and guarded each voucher check on the cast succeeding. A `P2PKVoucherSecret`
  is not a `VoucherSecret`, so the cast yielded null and **expiry and issuer-signature
  verification were silently skipped** - an expired voucher of that kind verified. It now reads
  through `VoucherMetadata`, which handles both kinds, so the opportunity for that class of bug
  is gone rather than patched. Regression test:
  `VoucherSpendingConditionTest#verify_ExpiredP2PKVoucher_ThrowsException`.

### Changed

- **`VoucherSecretDetector.isVoucherSecret` is renamed `isUnlockedVoucherSecret`**, and
  `carriesVoucherMetadata` is added. The old name answered `false` for something that *is* a
  voucher, which reads as disinformation; the new one says what it selects. Rules about which
  spending condition applies use `isUnlockedVoucherSecret`, while rules about what a proof *is*
  - Model B melt rejection and the mixed-proof-types check - use `carriesVoucherMetadata`.

---


## [0.34.2] - 2026-08-30

### Fixed

- Picks up cashu-vault 0.11.1, which widens `t_keyset.key_set_id` so a NUT-02 v2 keyset id fits.
  Provisioning a new mint derived a 66-character v2 id and the vault rejected the insert, so the
  provisioning outbox retried until it gave up and the mint was left with no keyset.

## [0.33.0] - 2026-08-29

### Added

- **The admin's fee handling is covered end to end** (#385). The provisioning path already carried
  `cashu.input_fee_ppk` through to the vault, but nothing asserted it, so an operator's configured
  fee reaching the keyset was untested. Also pinned: an unconfigured mint charges nothing, a
  negative or malformed fee is ignored rather than charged, a rotation reads the fee afresh so a
  fee change reaches wallets (ADR-0009), and the applied fee is recorded in the audit trail.

### Changed

- **The proof vault binding is named `hold_id` rather than `melt_saga_id`** (#403). A melt saga and
  a swap hold share it, which is what makes one block the other on the same proof, but the two
  resolve in opposite directions and the name admitted only one of them. Requires cashu-vault with
  migration V7.

### Changed

- **The mint issues NUT-02 version 2 keyset ids** (cashu-lib#247). A v2 id commits to the keyset's
  unit and `input_fee_ppk` as well as its keys, so provisioning at a different fee produces a
  different keyset rather than silently repricing an existing one. Safe to switch outright because
  no v1 keysets are deployed; a mint that had issued them would need a migration instead.

### Added

- **NUT-20 signed mint quotes** (#391). A wallet can lock a mint quote to a public key, and the
  mint then refuses to issue without a BIP-340 signature from the matching private key: a missing
  signature is `20009`, an invalid one `20008`. Without this a quote id is a bearer token, and
  quote ids travel through logs, webhooks and trace events, so anyone who reads one can take the
  ecash of a paid quote. The check runs before anything is signed, so a request that cannot mint
  leaves no issuance behind it. Locking is optional, so an existing wallet keeps working unchanged.

- **A NUT with published spec vectors now names them, and the advertisement is decided by whether
  they pass** (#402). A wiring witness that only resolves a class proves a class exists, which is
  what let NUT-11 be advertised `supported: true` while every published vector failed against it.
  The contract test now runs the named suite and requires the outcome to match the visibility, so
  vectors green with the NUT withheld fails the build, and so does the reverse.

- **Swap holds are now reconciled automatically** (#400). A swap that dies holding its inputs is
  resolved by `SwapHoldReconciler` rather than waiting for an operator. The hold records which
  side of the signing step it reached, because a stranded hold resolves in opposite directions
  depending on the answer and a held proof looks identical either way: one that never signed is
  released, one that may have signed is committed, never released. That direction is the opposite
  of the melt sweep, which releases a stale hold, because a melt is stranded before its
  irreversible step and a signing swap is stranded after it.

### Fixed

- **A swap can no longer leave signed outputs beside unspent inputs** (issue
  #400). `SwapTask` signed and stored every output in the signature vault before
  `InvalidateProofsTask` ran, with no transaction, compensation or rollback. If
  invalidation threw for any reason, the swap ended with the outputs retrievable
  through NUT-09 restore while the inputs were still `UNSPENT`, so the same value
  could be redeemed twice; and because invalidation looped proof by proof, a
  mid-list failure left the inputs partially spent.

  The swap now takes an exclusive `SwapProofHold` on its inputs *before* it
  signs, mirroring the melt saga and using the same vault primitives. A failure
  before signing releases the hold, so the wallet keeps its money and no
  signature exists. A failure after signing leaves the inputs `PENDING` and still
  bound, which no other swap or melt can claim, so the value cannot be doubled;
  the client is told `proofs_pending` and the hold is left for a reconciler or
  operator to commit. Claiming and committing are each one call for the whole
  input list, so the mid-list partial failure cannot arise at all.

  This does not weaken the #384 guarantee that validation precedes signing;
  `SwapTaskRejectionLeavesNoSignatureTest` still passes unchanged.

- **A SIG_ALL melt witness is now bound to the quote it pays** (issue #383).
  `MeltTask` still built its P2PK spending condition from the change outputs
  alone, so the `SigAllMessage.forMelt` aggregation that landed in `0563fb12`
  was never actually reached on the melt path and the quote id never entered the
  signed message. A `SIG_ALL` witness captured from one melt could therefore be
  replayed against a different quote. The melt path now constructs a
  `P2PKTransaction.forMelt` carrying the quote id, closing the replay gap the
  change was made to close.

  Evidence for the whole of #383 is in `SigAllTransactionBindingTest`: reordering,
  substituting, inflating, appending and dropping an output each invalidate the
  signature; a witness from one melt quote fails against another and vice versa;
  a melt witness does not verify as a swap; the uniformity precondition rejects
  inputs differing in data, tags or flag; and only the first input's witness is
  consulted. Each of those was confirmed load-bearing by mutating the
  implementation and observing the matching test fail.

- **Documented the SIG_ALL compatibility check** (issue #383). Fixing `SIG_ALL`
  correctly invalidates any deployed proof relying on the previous behaviour, so
  `docs/explanations/sig-all-compatibility-check.md` records what was actually
  inspected: Dalia's only `setSigFlag` call sets `SIG_INPUTS`, no live consumer
  constructs a `SIG_ALL` proof, and the one construction site is an archived
  voucher adapter whose proofs the mint rejects under Model B regardless.

### Known issues

- **The published NUT-11 vectors do not yet verify** (cashu-lib#254).
  `Nut11TestVectorsTest` drives every vector from `tests/11-test.md` and is
  `@Disabled` pending a cashu-lib fix. `WellKnownSecret` re-serializes secrets to
  a 4-element array instead of NUT-10's `[kind, {object}]`, so no conformant
  proof can verify here. The cryptography is correct: the same vectors verify
  against the raw wire secret, reproducing the spec's published SIG_ALL digest
  `de7f9e3c...`. This also means our `Y = hash_to_curve(secret)` diverges from a
  conformant wallet's, so the fix needs the dual-lookup migration described in
  `docs/explanations/spent-proof-key-encoding.md`. Un-disable the suite when
  cashu-lib#254 lands; it is the interoperability gate.

- **The Format check runs again, and now shares the build's JDK** (issue #360).
  The `Format` workflow had failed on every release PR since May. The failure was
  in `axel-op/googlejavaformat-action@v4`'s own `--version` preflight, so no
  repository source was ever examined: a second, independently-versioned Java
  toolchain had drifted away from the runner JDK. A permanently red check is
  worse than no check, because it teaches reviewers to merge past a red tick.

  The separate action is replaced by `spotless-maven-plugin`, bound to `validate`
  in the root POM. Formatting is now checked by the same Maven build, on the same
  JDK, as compilation, so the two toolchains cannot drift apart again. Run
  `./mvnw spotless:apply` to fix violations locally.

  The enforced rules are deliberately narrow - unused-import removal, trailing
  whitespace, final newline, and space indentation - which brought the tree into
  compliance in 56 files of import and whitespace deletions. Full Google Java
  Format would rewrite 611 of 615 files; that is tracked separately rather than
  landed here.

- **CI builds from clean, so stale classes cannot mask a broken tree** (issue
  #399). `ci.yml` ran `./mvnw verify` and `release.yml` ran `mvn deploy`, both
  without `clean`. Classes left in `target/` from an earlier build can satisfy
  references that no longer exist in the source, letting an incremental build
  pass where a fresh checkout would fail. Both workflows now run `clean`.

- **The amount limits `/v1/info` advertises are now the limits the mint enforces**
  (issue #390). `min_amount` and `max_amount` had moved out of the packaged YAML
  into `mint.capabilities.*` properties, but nothing read them except the info
  endpoint: the mint advertised `max_amount: 10000` and would issue a quote for
  any amount. The properties file carried a comment saying the values "MUST match
  the limits the deployment actually enforces", which is the hand-maintained
  promise the issue set out to eliminate.

  Both halves now go through one `AmountLimitPolicy`. `DefaultMintInfoService`
  builds the NUT-04/NUT-05 `methods` entries from it, and `MintQuoteTask` and
  `MeltQuoteTask` enforce against the same `PaymentMethodLimits` instances, so
  the advertised number is the enforced number by construction. An over-limit
  request is refused with `amount_outside_limit_range`, and a unit the mint
  advertises no limits for is refused with `unit_not_supported`. Mint requests
  are rejected before the gateway is asked for an invoice; melt requests are
  checked once the gateway has decoded the invoice amount.

- **NUT-19 no longer advertises `/v1/swap`, which has no response cache**
  (issue #390). The cached-endpoint list was a hardcoded `List.of(...)` outside
  the wiring-witness mechanism, and it named `/v1/swap`. NUT-19 promises that a
  replayed request returns the cached response; a replayed swap instead hits
  `ValidateTransactionTask.rejectAlreadySignedOutputs` and fails with
  `outputs_already_signed`. A wallet that read `/v1/info` and safely retried an
  interrupted swap got an error instead of its signatures.

  Cached routes are now `CachedEndpoint` constants, each naming the store it
  replays from (`IssuanceRecord.signaturesJson` for `/v1/mint/bolt11`,
  `MeltSaga.meltResponseCache` for `/v1/melt/bolt11`), resolved by reflection in
  `NutWiringContractTest`, so a path cannot be advertised as cached without the
  cache existing. `/v1/swap` was dropped rather than given a cache: a swap cache
  needs a durable store keyed on the outputs fingerprint and a replay path
  through the double-spend check, and over-advertising costs a wallet the retry
  it actually made while under-advertising costs it only one it could have made.

- **`mvn clean verify` from the repository root reaches `mint-admin-core`'s tests
  again** (issue #399). `KeyVault` gained `retrieveByAmount(BigInteger, String)`
  and `archive(String)` upstream, and the `RecordingKeyVault` test double in
  `VaultProvisioningAdapterRotationTest` was never updated, so test-compile
  failed. Incremental builds hid this because stale test classes still satisfied
  the old interface. The double now answers both from the keys the rotation
  actually wrote, matching the real vault's behaviour of throwing
  `CashuErrorException` when nothing matches rather than returning null, and two
  tests assert the rotation's keys are reachable the way the mint signs with them.

### Security

- **A proof spent before the `hash_to_curve` correction is still detected as
  spent** (issues #395, #396). The mint keys its double-spend store on
  `Y = hash_to_curve(secret)`. cashu-lib 0.22.0 corrected that encoding from
  hex-decoding the secret to hashing its UTF-8 bytes (cashu-lib ADR 0001), so the
  same legacy proof now computes a **different** `Y`. Taking the dependency
  without addressing this would have turned a compatibility fix into a
  double-spend hole: an already-spent legacy proof would find no row under the
  new key and be reported unspent.

  Lookup now checks both the `SPEC` and `LEGACY_HEX` values of `Y`, in
  `SecretEncoding.verificationOrder()`, and a spend is recorded under the key an
  existing record already uses so one logical proof never becomes two rows.
  `SpentProofKey` is the single place both questions are answered.
  `LegacyProofDoubleSpendTest` stores a spent proof under the legacy key only and
  asserts it is still reported SPENT after the upgrade, so removing the dual
  lookup fails the build rather than silently reopening the hole. Every
  `C = k*Y` check already routes through `BDHKEUtils.verify(String secret, ...)`,
  which walks the same encoding order. See
  [spending a proof exactly once across the secret encoding change](docs/explanations/spent-proof-key-encoding.md).

### Changed

- Upgraded `cashu-lib` from 0.21.0 to 0.22.0.

### Fixed

- **Multi-keyset input sets are priced correctly** (issue #396). `getFees` priced
  **every** input from the **first** input's keyset and guarded the keyset id with
  an `assert`, which is disabled at runtime by default. NUT-02 keeps inactive
  keysets spendable, so a transaction mixing keysets is routine and was being
  silently mispriced in either direction. Fees are now resolved per input through
  a `KeySetResolver` backed by `MintLoadService` (`MintKeySetResolver`), and an
  unresolvable keyset id raises `UnknownKeySetException`, which the REST layer
  maps to NUT-02 `keyset_not_known` (12001) with its own HTTP status instead of a
  generic internal error.

- **The mint's `ErrorResponse` no longer collides with cashu-lib's** (issue #396).
  Both declared a class at `xyz.tcheeric.cashu.entities.rest.ErrorResponse` with
  incompatible shapes. The local source shadowed the library's, so the split
  package was invisible to the compiler until anything disturbed it. The mint's
  class moved to `xyz.tcheeric.cashu.mint.proto.error`. Its `toJson()` also now
  serialises through Jackson: built with `String.format`, a message containing a
  quote or backslash produced invalid JSON and turned a reportable error into an
  unparseable body.

- **The mint accepts a wallet's own output split** (audit finding M3, issue #394).
  `MintTask` compared the requested outputs against the one canonical minimal
  split of the quote amount and rejected everything else with
  `invalid_denominations`. NUT-04 asks only that the outputs sum to the quote
  amount using denominations an active keyset can sign; it does not prescribe how
  the wallet splits that sum, and no external wallet computes the minimal split.
  This blocked every external wallet from minting at all, and was the first stage
  the Nutshell interoperability harness failed. Each output amount is now checked
  against the keyset's own denominations, and the sum check that was always there
  is unchanged.

- **One balance equation, checked before signing** (audit finding M2, issue #384).
  `VerifyProofsTask.validateAmounts` enforced `sum(inputs) == sum(outputs)` while
  `VerifyFeesTask` enforced NUT-02's `sum(inputs) - fees == sum(outputs)`. The two
  agree only when fees are zero, so the first keyset with a non-zero
  `input_fee_ppk` would have made every swap fail whichever equation the wallet
  satisfied. `validateAmounts` now validates only what its name says, positive
  amounts, and the balance equation lives solely in `VerifyFeesTask`.

### Security

- **A rejected swap leaves no blind signature behind** (audit finding M2, issue
  #384). `SwapTask` signed every blinded message before validating the balance,
  so an unbalanced swap was signed first and rejected afterwards, and its
  signatures stayed in the signature vault where NUT-09 restore would hand them
  back. Validation now runs before the signing loop. `SwapTask` calls no
  `@Transactional` method, so nothing rolled these back; the ordering was the
  whole defence.

### Added

- **Shared protocol validation for swap, mint and melt** (audit finding M5, issue
  #387). `ValidateTransactionTask` runs the input and output rules that
  `error_codes.md` names and this mint did not enforce: duplicate inputs
  (`11007`), duplicate outputs (`11008`), inputs or outputs of multiple units
  (`11009`), inputs and outputs of different units (`11010`), outputs on an
  inactive keyset (`12002`), and previously signed outputs (`11003`). Duplicate
  inputs were the serious one: `ProofLockManager` deduplicates the secrets it
  locks and the vault reported the same proof `UNSPENT` on both lookups, so a
  doubled 1000-sat proof could buy 2000 sats of outputs. Every rule is decided
  from the request, so the step runs before anything is signed.

- **`GET /v1/info` advertises the mint that is actually running** (audit finding
  M8, issue #390). The response was served from a `mint.yaml` packaged inside the
  jar: the mint called itself "Bob's Cashu mint", reported `Nutshell/0.15.0`, and
  published a `pubkey` and `.onion` address identical for every deployment of
  this codebase. The `nuts` map was wrong in both directions — NUT-19 had been
  implemented since spec 002 (`melt_response_cache`) and was never advertised, so
  wallets that could safely replay an interrupted melt did not know it. `/v1/info`
  is how a wallet decides what it may attempt, so this made wallets take code
  paths the mint could not honour.

  The advertisement is now **derived from the wiring** rather than written down.
  `NutSupport` pairs each NUT with the class (and where useful the member) whose
  existence makes the claim true, and `DefaultMintInfoService` builds the `nuts`
  map by walking it. `NutWiringContractTest` fails the build when a declared NUT
  loses its implementation, when a `@Nut`-annotated implementation is not
  declared, or when the served map disagrees with the registry. The previous
  guard compared the YAML against a hand-maintained constant, which could not
  catch either failure. See
  [why /v1/info is derived from the wiring](docs/explanations/mint-info-advertisement.md).

- **`POST /v1/checkstate` returns one state per requested `Y`, in request order**
  (audit finding M4, issue #386). The cross-mint merge was keyed on whatever the
  mints happened to know, so a wallet checking N proofs could get back fewer than
  N entries with no way to tell which were missing, and would silently misread
  which proof is spent. The merge is now keyed on the requested `Ys`: an unknown
  `Y` returns `UNSPENT`, the NUT-10 `witness` is carried through so a spent P2PK
  proof can be validated offline, and archived mints are consulted
  unconditionally rather than only when no active mint answered — reporting a
  spent proof as unspent is the dangerous direction of this error. Precedence
  `SPENT > PENDING > UNSPENT` is preserved. Extracted to
  `CrossMintCheckStateMerger` with unit tests.

### Changed

- **A DLEQ proof failure now fails the signing request instead of returning an
  unproven signature** (audit finding M7, issue #389). The mint advertises
  NUT-12, so a blind signature without a proof silently degraded a verifiable
  signature into an unverifiable one, indistinguishable to a wallet from a mint
  that never supported NUT-12. Every swap and mint response now carries a `dleq`,
  because any response that would not have is an error instead. Failures
  increment the new `cashu_mint_dleq_generation_failures_total` counter, so the
  condition is alertable rather than buried in a WARN line. See
  [Why the mint fails closed on DLEQ](docs/explanations/dleq-fail-closed.md).
- **The DLEQ nonce is derived deterministically per NUT-12**
  (`r = HMAC-SHA256(key=a, "Cashu_DLEQ_R_v1" || A || B' || C' || ctr)`, with the
  `ctr` rejection-sampling loop) rather than drawn from the RNG. Nonce reuse
  leaks the mint private key outright; deriving `r` from the key and the message
  removes that failure mode. Checked against the published NUT-12 vector.

### Added

- **An interoperability test drives an external Cashu implementation through
  mint, swap and melt against this mint** (audit finding M10, Milestone 0).
  `NutshellInteropIT` starts the reference Python implementation
  (`cashubtc/nutshell:0.16.5`) in a container and has it transact against a mint
  booted in the same JVM, with the dummy Lightning adapter and a scripted payment
  port standing in for a node. Published NUT vectors cannot settle whether our
  ecash is spendable elsewhere; only a foreign implementation can. It fails today
  and that is the point — it is the instrument, not the result. Two findings it
  already produced: the mint refuses any output split but the canonical minimal
  one, which no wallet chooses; and Nutshell's proofs fail our swap verification,
  the first external evidence for the `hash_to_curve` secret-encoding question
  (L1) that Milestone 1 has to answer. Without a Docker daemon it fails loudly
  rather than skipping, so a broken environment cannot hide a regression. See
  [Run the interoperability test](docs/how-to/run-the-interoperability-test.md).

### Documentation

- The NUT compliance audit gains an implementation plan: eight milestones, each
  ending at a releasable state, with all 21 findings assigned exactly once (18
  scheduled, 3 deferred). The ordering is driven by two constraints found in the
  code rather than by severity — `cashu-mint` consumes `cashu-lib` as a published
  artifact, so library work gates mint work; and the swap path has no
  transactional boundary, which makes the sign-before-validate fix a reordering
  rather than a rollback. Establishing the test vectors and an interoperability
  harness comes first, because the `hash_to_curve` encoding question cannot be
  settled without one and every later milestone changes crypto or wire format.

## [0.32.0] - 2026-08-28

### Added

- **An Operator can see a mint's keysets, so a rotation can be confirmed without
  reaching for `psql`.** `GET /admin/lifecycle/mints/{mintId}/keysets` answers
  what the shared vault holds — keyset id, unit, created-at, and whether the mint
  signs with it or has archived it — with the signing keyset first and archived
  ones newest-first, ordered server-side so the rule is stated once. The admin
  renders it at `/mints/:mintId/keysets`, reached from the mint detail page. A
  vault read that fails is a `502`, never an empty list: reporting the one as the
  other would tell an Operator that a rotation destroyed key material that is in
  fact still there. The archived badge reads "Archived · still redeems", because
  an Operator opens this page after a rotation afraid the old keyset went away.
  Key material is dropped in the vault adapter, so no layer above it holds a
  private key it could log or serialise. (#381, #382)

- Each keyset expands into its denominations, ascending, each carrying the vault
  path its key lives at. An Operator taking a backup needs to reach the private
  keys, and the admin cannot hand one over: the vault stores none in its own
  database, keys live in HashiCorp Vault behind credentials the admin does not
  hold, and asking the mint over HTTP is closed by ADR-0003. Where each key lives
  is what a backup actually needs. Archived keysets answer too, since past key
  material is precisely what a recovery wants (ADR-0004).

- `MINT_ADMIN` can run the operational controls. Operations are reachable only
  from a mint's detail page, which needs `mint:lifecycle`, so `OPS_ADMIN` alone
  could not get there and a `MINT_ADMIN` looking at a stuck mint needed a second
  role to act on it. The ACL test now reads permissions off the enum rather than
  restating them, so the two cannot drift apart again.

- Enrolling a browser key asks for the passphrase twice. A typo encrypts the key
  under a passphrase nobody knows, and the key lives only in that browser, so the
  confirmation happens before the save rather than on the next visit.

### Changed

- **The dev and E2E stacks run a vault-backed mint, so the admin and the mint are
  one system.** The mint served keysets from `preload-test-data.json` because
  `PreloadMintLoadService` is `@Primary` and on by default, and neither compose
  file disabled it. A mint the admin provisioned, and every rotation of its
  keyset, therefore landed in the vault and was invisible at `/v1/keysets`. Both
  stacks now set `MINT_PRELOAD_ENABLED=false` and point the mint at HashiCorp.
  `KeyRotationE2EIT` is no longer `@Disabled`: it asserts a rotation against the
  mint rather than against the admin's own echo.

- Seeding the dev keyset is separate from serving it. `VaultPreloadSeeder` seeds
  the vault at startup from the same JSON, so disabling preload no longer takes
  the bootstrap data with it. The `vault-db-seed` compose service is removed: it
  loaded SQL inserting `t_key.private_key`, a column dropped when key material
  moved to HashiCorp, so every run failed and seeded nothing. Key material cannot
  be seeded over SQL, because only the backend-aware vault knows where the secret
  goes.

- The operational controls listing reports `controlType` and `outcome`. Without
  them an operator could see that a rotation completed but not which keyset
  replaced which. The admin UI showed a permanently blank "Reason" column bound
  to a field the API never sent; it now shows the type and the outcome.

### Fixed

- **Provisioning no longer gives a unit a second active keyset.** A mint whose
  keyset was established by something other than the create-mint saga would get a
  second one, leaving two keysets claiming to sign for the unit; the next
  rotation then archived both and could not say which it replaced. An existing
  active keyset now stands.

- Key rotation no longer fails on every attempt. The vault permits a mint one
  *active* keyset per unit, so provisioning the replacement before archiving the
  keyset it replaces was rejected outright and every rotation ended
  `KEY_ROTATION_FAILED`. A rotation now archives its predecessor first and
  reinstates it if the replacement cannot be provisioned, so a failure part-way
  never leaves the mint unable to sign. A mint holding no keysets is treated as
  nothing to supersede rather than as a vault failure.

- The dev stack no longer re-creates the constraint that makes rotation
  impossible. A `vault-db-init` service applied `V1__init_schema.sql` with `psql`
  on every `up`, restoring the `UNIQUE (unit, mint_id)` index that migration `V5`
  drops precisely so a rotated-away keyset can coexist with its replacement. The
  vault schema is owned by Flyway inside `cashu-vault-jpa`, so the service is
  removed. An existing dev database needs
  `DROP INDEX IF EXISTS idx_keyset_unit_mint_unq;` once.

- A mint nobody has provisioned yet reports as empty rather than as the
  Operator's own typo. The vault client signals "this mint holds no keysets" two
  different ways — an `IllegalArgumentException` for an empty body on a `200`, and
  a `404` from the running vault — and the service caught the first alongside a
  malformed mint id, answering `400 invalid_mint_id`. Both signals now translate
  to an empty list in the adapter, so the page's not-provisioned state is
  reachable against a real vault.

- Every Operator role can read the audit trail. The dashboard's recent-activity
  panel is built from it, so `USER_ADMIN` and `OPS_ADMIN` — holding
  `dashboard:read` without `audit:read` — landed on a first screen whose main
  panel reported forbidden, and the audit page refused them outright. The trail is
  read-only and is how an Operator checks what was done to the deployment they are
  on call for.

- The admin gates the mint and operations pages on the permission rather than the
  role. The Super Administrator holds every permission and none of those pages'
  roles, so the account that exists to recover a deployment saw a dashboard and
  nothing else. A page can no longer be granted by the API and refused by the UI.

- Signing out wipes the enrolled browser key. It cleared the session but left the
  encrypted key in `localStorage`, so the login page still saw a stored key and
  offered only a passphrase box — the next person could not sign in with their own
  key at all. An idle lock still keeps the key on purpose, since the same Operator
  is coming back; signing out is how someone hands the browser over.

- The admin Docker image builds. The Dockerfile named a module path and a jar name
  that do not exist, and `.dockerignore`'s `**/out` swallowed the hexagonal
  `port/out` and `adapter/out` packages — 50 source files — out of every build
  context.

### Documentation

- **Audited `cashu-mint`, `cashu-lib` and `cashu-wallet` against the NUT
  specifications at pinned commit `49a909c`, and recorded the result in
  `docs/explanations/nut-compliance-audit.md`.** Fourteen divergences, tracked as
  21 issues across the three repositories. The interoperability-breaking ones are
  the secret encoding fed into `hash_to_curve`, the non-spec error wire format,
  and NUT-11 `SIG_ALL`, which verifies each input and output separately instead of
  signing one aggregated message. Fees turn out not to be wired up at any layer:
  `input_fee_ppk` is never set, is absent from `/v1/keysets`, is priced off the
  wrong keyset, is ignored by the wallet, and the swap path enforces two
  contradictory balance equations that only agree when the fee is zero. Keyset ID
  v2 and NUT-20 are carded rather than scheduled.

- **Audited every document against the code and fixed or deleted what no longer
  described it.** The admin API reference listed 13 endpoints that do not exist
  (all of `/admin/alerts/*`, `/admin/configuration/*`, `/admin/health/*`) and
  omitted two that do; it is now generated against the controllers and states the
  real roles, permissions and pagination fields. `admin-rest-api.md` is deleted as
  a strict duplicate. The E2E how-to prescribed a command that cannot work: the
  suite starts its own Testcontainers stack and needs `-Pe2e-tests -am`. Docs
  still described `vault-db-seed`, the SQL seeding path, and gateway classes and
  artifacts under their pre-rename names.

- **`audits/` removed, and the local-only `project/` and `specs/` directories
  deleted from the working tree.** Both were already gitignored. The operator
  procedure that lived in `specs/004/quickstart.md` — salt generation, backfill,
  retention purge, forensic lookup, salt rotation — is absorbed into
  `docs/runbooks/voucher-data-minimisation.md`, which was previously a pointer to
  it. Ten Javadoc citations of `specs/` paths now state their rule inline, and two
  runtime error messages point at the runbook instead of a file that no longer
  exists.

- The admin user guide drops the sections documenting removed features (alerts,
  health monitoring, configuration governance) and the invocations of a CLI that
  was deleted, losing a third of its length. `admin-triage.md` is re-verified:
  rotation and RBAC now actuate, retirement stops signing, and mint-side
  suspension exists but nothing in the admin writes it.

### Changed

- **The dev and E2E stacks run a vault-backed mint, so the admin and the mint are
  one system.** The mint served keysets from `preload-test-data.json` because
  `PreloadMintLoadService` is `@Primary` and on by default, and neither compose
  file disabled it. A mint the admin provisioned, and every rotation of its
  keyset, therefore landed in the vault and was invisible at `/v1/keysets`. Both
  stacks now set `MINT_PRELOAD_ENABLED=false` and point the mint at HashiCorp.
  `KeyRotationE2EIT` is no longer `@Disabled`: it asserts a rotation against the
  mint rather than against the admin's own echo.

- Seeding the dev keyset is separate from serving it. `VaultPreloadSeeder` seeds
  the vault at startup from the same JSON, so disabling preload no longer takes
  the bootstrap data with it. The `vault-db-seed` compose service is removed: it
  loaded SQL inserting `t_key.private_key`, a column dropped when key material
  moved to HashiCorp, so every run failed and seeded nothing. Key material cannot
  be seeded over SQL, because only the backend-aware vault knows where the secret
  goes.

- The operational controls listing reports `controlType` and `outcome`. Without
  them an operator could see that a rotation completed but not which keyset
  replaced which. The admin UI showed a permanently blank "Reason" column bound
  to a field the API never sent; it now shows the type and the outcome.

### Fixed

- **Provisioning no longer gives a unit a second active keyset.** A mint whose
  keyset was established by something other than the create-mint saga would get a
  second one, leaving two keysets claiming to sign for the unit; the next
  rotation then archived both and could not say which it replaced. An existing
  active keyset now stands.

- Key rotation no longer fails on every attempt. The vault permits a mint one
  *active* keyset per unit, so provisioning the replacement before archiving the
  keyset it replaces was rejected outright and every rotation ended
  `KEY_ROTATION_FAILED`. A rotation now archives its predecessor first and
  reinstates it if the replacement cannot be provisioned, so a failure part-way
  never leaves the mint unable to sign. A mint holding no keysets is treated as
  nothing to supersede rather than as a vault failure.

- The dev stack no longer re-creates the constraint that makes rotation
  impossible. A `vault-db-init` service applied `V1__init_schema.sql` with `psql`
  on every `up`, restoring the `UNIQUE (unit, mint_id)` index that migration `V5`
  drops precisely so a rotated-away keyset can coexist with its replacement. The
  vault schema is owned by Flyway inside `cashu-vault-jpa`, so the service is
  removed. An existing dev database needs
  `DROP INDEX IF EXISTS idx_keyset_unit_mint_unq;` once.

## [0.31.0] - 2026-08-28

### Changed

- **BREAKING** The admin API is reachable only with a NAP session. The shared
  admin token, the path-to-role RBAC filter, the bootstrap identity that held
  every role and the bespoke `/admin/auth/me` endpoint are deleted; NAP's
  `/api/v1/auth/session` replaces the last of them. Every admin controller now
  names the permission it requires, so an Operator without it is refused with a
  `forbidden` error body rather than a bare status. Per-operator credentials and
  their reset workflow are gone with the token: a migration drops
  `credential_hash`, `reset_count` and `reset_requested_at`, deletes rows with no
  public key, and makes `pubkey` required. The Super Administrator is configuration,
  not data: the role cannot be written through the admin API and a stored profile
  carrying it is ignored, so an Operator who may edit roles can no longer grant
  themselves the role that outranks them. Both admin API test suites sign in
  through a real handshake behind one helper, so nothing authenticates in tests
  that could not authenticate in production. Nothing is migrated and no fallback
  is kept — no deployment exists to migrate. (#373)

- Admin audit entries name the Operator who acted, taken from the authenticated
  session rather than from a request field. A caller could previously attribute
  their own action to anyone. (#370)

### Added

- Operators can authenticate to the admin API with their Nostr key (NAP), alongside
  the existing token. A completed NIP-98 handshake yields a session whose role and
  permissions come from one `AclResolver`: the configured Super Administrator npub
  (read from the environment as bech32, compared as hex, never looked up) first,
  otherwise the Operator profile. An npub with no profile, or a suspended one, is
  refused — authenticating grants no default role. Startup fails when the
  super-admin npub is missing or does not decode. Role and permission names are
  declared in `mint-admin-core`, which has no NAP dependency; NAP's own `AclStore`
  is unused, since it cannot list, update or delete and so cannot express Operator
  management. NAP's migrations run unmodified in a `nap` schema with their own
  history table; the admin's moved to `db/migration-admin` so the recursive scan
  cannot pick them up. Operator profiles gained a nullable `pubkey` column.
  Sessions are 15 minutes idle / 12 hours absolute with refresh tokens off, on an
  `HttpOnly`, `SameSite=Lax`, production-`Secure` `cashu_admin_session` cookie.
  On by default (`NAP_ENABLED`), and since #373 the only way in. (#372)

- The Super Administrator can provision, re-role, suspend and reinstate Operators
  through `/admin/users`. Suspension rather than deletion: an Operator named in the
  Audit Trail has to stay resolvable, so the row survives with `active = false` and
  the resolver refuses them on their next request. SUPER_ADMIN cannot be assigned
  through the API and the configured Super Administrator cannot be modified through
  it, so the account that recovers the deployment cannot be edited out of existence
  by someone who may edit roles. Every change writes an `operator_access_audit` row
  in the same transaction as the change itself. (#374)
- Operators sign in to the admin UI with a NIP-07 browser extension, or with a key
  encrypted in the browser under a passphrase for machines with no extension. The
  in-browser key is held only for the session; a reload asks for the passphrase
  again rather than keeping a decrypted key at rest. (#375, #376)
- An Operator management page: the listing names each Operator by npub, shows the
  configured Super Administrator marked as configuration-anchored rather than
  editable, and offers suspend / reinstate with a reason. The page is gated on the
  `users:manage` permission rather than a role, so the Super Administrator — who
  holds every permission and no page's role — reaches the pages they exist for. (#378)
- ADR 0008 records why authorisation is resolved by the admin rather than delegated
  to NAP's `AclStore`, and a how-to covers configuring the super-admin npub, the two
  startup failures, and enrolling the first Administrator. (#379)

### Fixed

- Dropped a readiness alert that could never fire: it tested an expression no
  exporter in the stack publishes, so it read as coverage while watching nothing.

- The mint could not boot with `cashu.mint.jpa.enabled=true`. `byte-buddy` was
  pinned to `test` scope in the parent `dependencyManagement`, which overrides
  the compile-scope transitive dependency Hibernate declares for its
  `BytecodeProviderImpl`, so the jar never reached the runtime image. The mint
  started, applied all 18 Flyway migrations, and only then failed building the
  `EntityManagerFactory` with `NoClassDefFoundError:
  net/bytebuddy/description/type/TypeDefinition`. Every unit-test boot and every
  dev profile ran without JPA, so nothing exercised the path. `byte-buddy-agent`
  stays test-scoped; only Mockito uses that one.

- Grafana dashboards no longer read empty. Three independent faults each broke
  the pipeline on their own, and all three were invisible because the failure
  mode of every one of them is a blank panel rather than an error:
  - Task metrics were tagged `task` while every dashboard, alert rule and the
    metrics reference grouped by `task_name`. The series existed, so nothing
    errored; `sum by (task_name)` just silently collapsed every task into one
    unlabelled line.
  - Request, task and lock timers published no histogram buckets, so Micrometer
    exported Prometheus *summaries*. Summaries carry count, sum and max but no
    `_bucket` series, so every `histogram_quantile` panel and every latency SLO
    alert resolved to no data.
  - Prometheus scraped a hardcoded `cashu-mint-rest-dev:9000`, a hostname that
    resolves only on the dev stack. Anywhere else no target matched, which makes
    `up{job="cashu-mint"}` *absent* rather than `0`, so even `CashuMintDown`
    stayed quiet.
- The two voucher dashboard panels that query Prometheus had a datasource with
  no `uid`, so they resolved against whichever datasource Grafana defaulted to.

### Added

- `RuntimeClasspathTest` asserts that `byte-buddy` and `hibernate-core` are on
  the *runtime* classpath, so the JPA boot path cannot lose its bytecode
  provider again. It reads the classpath recorded by `maven-dependency-plugin`
  rather than calling `Class.forName`, because a test-scoped dependency is
  present on the test classpath and an in-JVM check passes while the image is
  broken. Verified in both directions: red with the scope restored, green with it
  removed.
- `ScrapeContractTest` guards the three runtime-composed metric families
  (`cashu_mint_requests_*`, `cashu_mint_task_*`, `cashu_mint_lock_*`) that
  `MetricCatalogueContractTest` exempts by design. It drives the real
  instrumentation into a real `PrometheusMeterRegistry` and asserts against the
  actual scrape text that every timer family exports `_bucket` series and that
  every label a dashboard or alert groups or filters by exists on the emitted
  series. The exempted families were the only ones nothing checked, and all
  three had drifted.
- `Cashu Mint Integrity` dashboard, covering the 15 of 17 declared recorder
  metrics that no dashboard charted — including every money-at-risk invariant
  (`cashu_mint_melt_payment_sent_burn_failed`,
  `cashu_mint_melt_stuck_payment_unknown`, `cashu_mint_voucher_orphan_issuance`)
  that a `critical` alert fires on. Those alerts previously paged with no
  dashboard to land on. It also charts
  `cashu_mint_invariant_poll_failures_total` beside those gauges, since a stale
  poll leaves them reading a falsely reassuring zero.
- Prometheus scrape targets now come from file service discovery
  (`prometheus/targets/*.yml`, overridable with
  `CASHU_PROMETHEUS_TARGETS_DIR`), so one config works across dev, staging and
  production.

### Changed

- Dashboard titles say which question each one answers, and the mint dashboards
  are prefixed so they group together in Grafana's list: `Cashu Mint — Service
  Health`, `— Task & Endpoint Breakdown`, `— SLO & Error Budget`, `— Lock
  Contention & Threads`, `— Money at Risk`, and `Vouchers — Outstanding
  Liability` / `— Token Reconciliation` / `— IOU Exposure`. UIDs are unchanged:
  they are the stable link target for bookmarks and alert `runbook_url`
  annotations, so renaming one breaks every existing link silently.

### Removed

- `Cashu Mint Business` dashboard, superseded by `Cashu Mint Integrity`. Its two
  panels charted a cumulative counter as a bare total and its rate; both are
  retained on the new dashboard, broken down by funding source.

## [0.30.0] - 2026-08-19

### Removed

- **BREAKING** `mint-admin-cli` module, and the `/admin/health`, `/admin/alerts`
  and `/admin/configuration` endpoints. None of them worked: health had no
  producer and read `UNKNOWN` forever, alerts held only hand-typed rows with no
  detection or delivery, configuration was stored and never applied, and the CLI
  presented stub data as live in connected mode. Monitoring is
  `cashu-mint-observability`'s job. The `ALERTS_ADMIN` role is gone.
  `ConfigurationSet` and `NotificationPolicy` are kept — they are `MintAggregate`
  state, not feature state. (#364)

### Changed

- **BREAKING** Admin authorisation no longer reads roles from the `X-Admin-Roles`
  request header. Operators authenticate with their own credential and their
  roles are resolved from the operator store. Credentials are random and stored
  only as SHA-256 hashes; every previously issued reset token stops working.
  `/admin/audit/**` now requires a role. (#362)
- **BREAKING** `MintProtocolService` gains `requireActiveKeySet`. The mint refuses
  to sign new outputs against an archived keyset, returning `keyset_inactive`.
  Redemption of proofs an archived keyset already signed is unaffected. (#361)

### Added

- Key rotation actually rotates: `ROTATE_KEYS` drives an outbox saga that
  generates a replacement keyset in the shared vault and archives the one it
  supersedes, idempotent on the operational control id, with compensation if the
  archive step fails. Outcomes are recorded in `operational_controls.outcome`
  with both keyset ids. (#363)

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.29.0] - 2026-08-18

Replaces the mint's dead instrumentation with a typed recorder seam, adds
DB-derived gauges for the three money-losing invariants, and makes both
directions of the metric catalogue checkable by CI (#337).

### Removed

- **BREAKING** `MintIntegrityContext.meterRegistry()`. Deleted, not deprecated:
  a deprecated accessor preserves the exact hole this work exists to close —
  the next spec adds counter number seventeen inline and the catalogue drifts
  again. Domain code reaches metrics only through the typed recorder ports in
  `xyz.tcheeric.cashu.mint.proto.metrics`. The registry no longer threads
  through `NUT04 → MintTokensTask → MintTask`, so the public constructors of
  both tasks lost their trailing `MeterRegistry` parameter, and the null checks
  that guarded every former call site are gone (#343).

### Changed

- **BREAKING** Metric renames. Dashboards and alert rules querying the old
  names return no data:
  - `cashu_mint_voucher_funding_required_total`,
    `cashu_mint_voucher_face_value_not_backed_total` and
    `cashu_mint_voucher_iou_denied_total` collapse into
    `cashu_mint_voucher_rejected_total{reason=...}`, with the label domain
    bounded by an enum. They were always one metric with a reason dimension;
    splitting them meant a new reason cost a new metric, panel and alert rule
    (#341).
  - `cashu_mint_quote_expired_total` → `cashu_mint_issuance_quote_expired_total`
  - `cashu_mint_amount_mismatch_total` → `cashu_mint_issuance_amount_mismatch_total`
  - `cashu_mint_quote_cross_check_failures_total` → `cashu_mint_issuance_cross_check_failure_total`
  - `cashu_mint_idempotent_replay_total` → `cashu_mint_issuance_idempotent_replay_total`
- **BREAKING** The `path="mint"` label is gone from the issuance counters. With
  the area in the metric name it was a second encoding of the same fact (#342).
- **BREAKING** `cashu_mint_voucher_rate_limit_breach_total` no longer carries a
  `principal` label. Today's single ADMIN service account keeps it bounded, but
  under per-merchant authentication it becomes both a cardinality problem and a
  data-minimisation one: spec 004 kept merchant identity out of the read path
  with column-level GRANTs, and a Prometheus label routes it straight back out
  into a store with no retention purge. The principal stays in the logs (#341).
- The metrics reference is now generated from the recorder declarations rather
  than written by hand. It had documented roughly fifty metrics of which most
  were never emitted, because it was a third artefact with nothing tying it to
  the code (#348).

### Added

- Typed metric recorder ports for the voucher, mint/issuance, webhook and
  invariant areas, with Micrometer implementations in `cashu-mint-observability`
  (#341, #342, #343). Every meter is registered eagerly, so no family first
  materialises on failure — a family that only appears when something breaks
  reads as "no data", which is indistinguishable from a broken exporter.
- `InvariantGaugePoller` in `cashu-mint-jpa`: exports the three money-losing
  invariants as gauges re-derived from the database every 60 seconds, running
  the operator queries already documented on the repositories (ADR 0002).
  A counter incremented on a state transition can neither express "stuck for an
  hour" nor survive a restart with its standing count intact — exactly the
  wrong failure mode for conditions that by design never resolve themselves
  (#344, #345).
  - `cashu_mint_melt_stuck_payment_unknown` — sagas parked in `PAYMENT_UNKNOWN`
    past `cashu.mint.melt.payment-unknown-ttl`.
  - `cashu_mint_melt_payment_sent_burn_failed` — payment settled while the
    proofs stayed spendable.
  - `cashu_mint_voucher_orphan_issuance` — issued voucher value with no funding
    row.
  - `cashu_mint_invariant_poll_failures_total` — the gauges fail open, so a
    failing poll has to be alertable in its own right or a stale zero silently
    disarms the pages.
- Alert rules for all three, each carrying the SQL behind its gauge in a
  `query` annotation so the first diagnostic step ships with the page. Their
  shapes differ deliberately so an on-call reader can tell a stuck saga from
  one merely in flight: burn-failure pages on the first occurrence, Orphan
  Issuance sustains five minutes, Stuck Payment applies its TTL in SQL.
- `MetricCatalogueContractTest` — fails the build when a declared metric has no
  production call site, or when a name in dashboard PromQL or an alert rule
  resolves to no declaration. Nothing connected a declared metric to a consumer
  in either direction before; both failure modes had happened and both survived
  CI for a long time (#347).
- `MeterRegistryContainmentArchTest` — fails the build if a protocol class
  depends on `io.micrometer` again (#343).
- New configuration: `cashu.mint.invariant.poll-interval` (default `PT60S`).

### Fixed

- Both melt invariant gauges excluded operator-acknowledged sagas only after
  review. `MeltSagaAdminController#markResolved` deliberately appends a
  transition without overwriting `current_state` (FR-007 / FR-011), and nothing
  else moves a saga out of `PAYMENT_UNKNOWN` or `PAYMENT_SENT_BURN_FAILED` — so
  as first written, either alert would have paged forever with no in-product
  way to clear it.
- The Stuck Payment clock now runs from the last transition *into*
  `PAYMENT_UNKNOWN` rather than from `created_at`, so an old saga that has only
  just turned ambiguous is not reported as stuck for hours.
- `CashuMintErrorBudgetBurning`'s description contained `{{ 30 / 10 }}`, which
  is not valid Go template syntax, so the one alert whose job is explaining how
  fast the budget burns would have rendered without its explanation. Found by
  the promtool rule tests added in the deployment repository — nothing had ever
  evaluated these rules.
- The integration-test Postgres container had lost `fsync=off`: Testcontainers
  sets it in the constructor and `withCommand` replaces rather than appends.

## [0.28.0] - 2026-08-18

### Changed

- **BREAKING for anything probing or scraping actuator on port 7777.** Actuator moved to
  its own management port, default `9000` (`CASHU_MINT_MANAGEMENT_PORT`). Nothing under
  `/actuator/**` answers on the public API port any more, so `/actuator/prometheus` — which
  exposes issuance rates, outstanding liability and melt-saga failure counts — is no longer
  readable by anyone who can reach the mint. External stacks consuming
  `docker.398ja.xyz/cashu-mint-rest` with a health check or load-balancer probe on
  `7777/actuator/health` must retarget to the management port, or probe `/v1/info` on 7777.
  `management.server.address` defaults to `0.0.0.0`: isolation comes from not publishing the
  port, which also keeps Kubernetes `httpGet` probes (issued against the pod IP, never
  loopback) working. `ManagementPortGuard` fails startup if the management and application
  ports are ever set equal. Issue #346.

### Added

- `ManagementPortGuard` — fails startup when `management.server.port` equals
  `server.port`, is unset, or is `-1` (which disables the management server and folds
  actuator back onto the public port). The port separation is the security property this
  release exists for, and it was previously undoable by a single environment variable with
  no warning at boot.
- `ActuatorManagementPortIT` — asserts `/actuator/prometheus` responds on the management
  port and 404s on the application port, alongside health/readiness still reachable for
  container health checks. It drives `CASHU_MINT_MANAGEMENT_PORT` rather than
  `management.server.port`, so it exercises the shipped wiring: setting the Spring property
  directly would pass against a build with no management port configured at all.

## [0.27.0] - 2026-08-18

### Removed

- **Four metric classes that registered ~45 meters and were never called once.**
  `MintMetrics`, `QuoteMetrics`, `VoucherMetrics` and `GatewayMetrics` were wired
  as beans in `ObservabilityAutoConfiguration`, but every reference to them
  outside `cashu-mint-observability` was a doc or their own unit tests — no
  production call site incremented a single counter. The dashboards and alert
  rules charted that dead set, so those alerts could never fire, while the
  metrics the mint does emit appeared on none of them. Deleted the classes,
  their unit tests and their bean definitions. `TaskMetrics` and `LockMetrics`
  stay: they are the two families reached through the `TaskMetricsAdapter` /
  `LockMetricsAdapter` recorder ports in `cashu-mint-protocol`, and the only two
  that were alive.
- Configuration properties that only fed the deleted beans:
  `cashu.observability.metrics.track-keysets`,
  `cashu.observability.metrics.include-unit-tag`,
  `cashu.observability.metrics.application`,
  `cashu.observability.metrics.environment`,
  `cashu.observability.vouchers.enabled` and
  `cashu.observability.gateway.enabled`. The `MetricsProperties`,
  `VouchersProperties` and `GatewayProperties` nested classes are gone with
  them. Setting any of these keys was already a silent no-op.
- The **Cost Analysis** Grafana dashboard, whose every panel queried a
  `GatewayMetrics` or `MintMetrics` series.
- Dead panels from the **Overview**, **Operations** and **Business** dashboards.
  The **SLO**, **Virtual Threads** and three voucher dashboards are untouched.
- Alert rules reading deleted series: `CashuMintHighLiability`,
  `CashuMintDoubleSpendAttempts`, `CashuMintQuoteBacklog`,
  `CashuMintProofIssuanceSpike` and `CashuMintVoucherRejectionRate`, plus
  `CashuMintGatewayUnhealthy` and `CashuMintVaultUnhealthy` — the latter two
  watched `cashu_mint_gateway_health` (from the deleted class) and
  `cashu_mint_vault_health` (never emitted by anything at all). The
  corresponding Alertmanager inhibit rules went with them.
- The spec-004 `cashu_mint_voucher_compat` Prometheus recording-rule group. It
  aliased the deleted plural `cashu_mint_vouchers_*` names and was already
  marked for removal after one release cycle. Nothing in this repo consumed the
  aliases; external consumers of `cashu_mint_vouchers_issued_total` lose that
  series with no deprecation window.

### Fixed

- **The JPA context could not boot at all when `cashu.mint.jpa.enabled=true`.**
  `V20260601_007` (spec 035, shipped in 0.26.0) added `original_token_amount` to
  `voucher_quote` but not to its Envers shadow `voucher_quote_aud`, while
  `VoucherQuoteEntity` is `@Audited` at class level. Hibernate's schema
  validator expects the column on both, so `mintEntityManagerFactory` failed to
  build and took the whole application context down — every integration test
  that boots the stack errored on `Unable to start embedded Tomcat`. Added
  `V20260601_008` to bring the shadow table in line. Audited by the same check
  across all seven `@Audited` entities; this was the only drift (the `version`
  columns are `@Version`, which Envers excludes by design).

### Changed

- Updated `cashu-voucher` to 0.10.0 (from 0.6.1).
- `cashu-mint-rest` no longer attaches its ~110 MB `-exec` fat jar as a Maven
  artifact (`<attach>false</attach>` on the Spring Boot `repackage` goal). The
  jar is still built into `target/` — the module's Dockerfile and
  `scripts/heap-exhaustion-test.sh` both read it from there — but it is no
  longer installed or deployed, where it exceeded the repository upload limit
  and failed `mvn deploy` with HTTP 413. The thin `cashu-mint-rest` jar is
  still published for use as a dependency.
- Integration tests raise `cashu.mint.issuance.rate-limit.per-minute-burst`
  in the shared `test` profile. Spring's `ApplicationContext` cache is shared
  across IT classes in a JVM fork, so `IssuanceRateLimitFilter`'s token bucket
  is shared too and later classes hit `POST /v1/mint` against an already-drained
  bucket. The filter stays enabled so it is still exercised.
- `cashu-mint-observability/docs/metrics-reference.md`, `docs/reference/configuration.md`,
  `docs/reference/module-layers.md`, `docs/how-to/enable-observability.md` and the
  Observability section of `CLAUDE.md` now describe only metrics the mint emits.
  Added the previously undocumented `cashu_mint_lock_*` family. Noted explicitly
  that the gateway and vault health indicators surface on `/actuator/health`
  only and are not exported as Prometheus series, so neither is alertable
  without an external probe.

Every `cashu_mint_*` name still referenced by dashboard JSON or an alert rule
now resolves to something the mint actually emits.

## [0.26.0] - 2026-08-18

### Fixed

- **P2PK spends were rejected for every spec-conformant public key.** `P2PKSpendingCondition`
  passed the 33-byte compressed key straight to `Schnorr.verify`, which requires the 32-byte
  BIP-340 x-only form and throws on anything else. That throw landed in a `catch (Exception)` that
  logged and continued, so the key silently counted as *no valid signature* and the spend failed as
  under-signed. The mint therefore only worked against 32-byte x-only keys — the form NUT-11
  forbids, and the form its own test fixtures built. The parity prefix is now stripped via the
  `xCoordinate` helper that already sat one method away (it was used for dedup counting but not for
  the verify call). Wallets sending compressed keys — cashu-ts, CDK, imani-wallet-lib — were
  affected.

### Added

- **A malformed P2PK lock returns an unspendable-proof error instead of a 500.** NUT-11 frames a
  malformed P2PK secret as a Proof that MUST be rejected as unspendable — a client error, not a
  server fault. Two handlers on `CashuController`: one for `MalformedP2PKSecretException` itself
  (service-layer parsing), and one unwrapping it from `HttpMessageNotReadableException`, since
  Jackson wraps deserializer exceptions during `@RequestBody` binding and it never arrives as
  itself from a request body. Both return `verify_proof_failed_error` with HTTP 400; an unrelated
  unreadable body stays an ordinary 400.

### Changed

- Updated cashu-lib to 0.21.0 (NUT-11 P2PK secret validation). Validation is fail-closed: a
  malformed lock is now rejected at parse time rather than accepted and misbehaving later. Test
  fixtures that built locks from 32-byte x-only keys were corrected to compressed keys.

## [0.25.0] - 2026-07-13

### Added

- **NUT-11 refund path now honors `n_sigs_refund`** (multi-sig refund threshold),
  consuming cashu-lib 0.19.0 — an escrow with `n_sigs_refund > 1` now requires that
  many valid refund signatures to reclaim, instead of any single one. Enforced only
  after `locktime` and counted over **distinct** refund pubkeys, on both the input
  refund signatures and `SIG_ALL` output witnesses. Backward compatible: escrows
  without `n_sigs_refund` set default to a threshold of 1, identical to the prior
  behavior. **`n_sigs_refund` is a Dalia extension to NUT-11** (not in the published
  spec): it is a structurally valid NUT-10/11 tag and backward compatible, but the
  multi-sig refund guarantee is enforced only by mints that implement this tag — a
  standard mint would ignore it and permit a single-signature (1-of-N) refund.

## [0.24.0] - 2026-07-13

### Added

- **Dalia zero-value IOU keyset (Phase 9)** — a dedicated keyset (`unit="iou"`)
  whose issuance is blind, payment-exempt, and allows `amount==0` markers, while
  all melt/swap of IOU proofs is refused (issuance + checkstate only; IOU tokens
  are non-transferable). The mint never inspects the IOU secret. Marked by
  convention via `IouKeysets`.
- **Per-identity mint issuance rate limit (Phase 9)** — a `/v1/mint/**` filter with
  a per-minute burst (default 10) + per-day quota (default 60) per identity, keyed
  by the engine-supplied `X-Dalia-Identity` header else the remote address. 429 +
  `Retry-After` + a Micrometer breach counter. Configurable under
  `cashu.mint.issuance.rate-limit.*`.

### Fixed

- **NUT-11 locktime semantics** — the primary n-of-m multisig is now spendable at
  any time (before/after locktime); the refund keys reclaim only after the
  locktime (or, absent refund keys, the proof unlocks). Previously a valid
  multisig spend was rejected during the lock window and the refund path never
  worked. **NUT-11 P2PK is now enforced at melt (redemption)**, not only at swap —
  previously melt performed a BDHKE check only, so a P2PK-locked proof could be
  cashed out without a valid witness.
- **NUT-11 P2PK determinism + error handling** (review hardening) — the secret is
  now hashed as **UTF-8** (was the platform default charset) on both the primary
  and refund paths, so witness verification is portable across environments. A
  malformed `sigFlag` and missing/unsigned outputs under `SIG_ALL` are now rejected
  as **protocol errors** (`CashuErrorException`) instead of surfacing as HTTP 500s.

### Security

- **Issuance rate-limit identity hardening** (review) — the client-supplied identity
  header is stripped of control characters (CR/LF log-injection defense) and capped
  in length before use as a cache key + log field, bounding per-identity memory.

---

## [0.23.0] - 2026-06-21

### Added

- **Traceability producer (spec 036)** — the mint can emit signed `kind-9079`
  trace events to the `cashu-ledger` forensic ledger: `MINT_QUOTE_REQUESTED` /
  `MELT_QUOTE_REQUESTED` on quote creation, and `MINT_FAILED` (no proofs) /
  `MELT_FAILED` (released inputs by public `Y` only) on the two failures the
  mint owns. Disabled by default (`cashu.trace.publisher.enabled=false`); when
  enabled, boot fails closed unless the signing key, relays, and `cashu.mint.url`
  are set. Fire-and-forget — a tracing fault never fails or blocks a mint
  operation. New code is confined to `cashu-mint-rest`; see
  `docs/how-to/enable-trace-producer.md`. Consumes the
  `cashu-ledger-trace-publisher` starter.

### Changed

- Bumped `nostr-java` `1.3.0` → `2.0.7` (the trace publisher signs over
  `nostr-java-core` 2.x; 2.x consolidated the module set into
  `core`/`event`/`client`/`identity`).
- `AsyncConfig` now enables `@Async` unconditionally (only the virtual-thread
  executor stays conditional), so disabling virtual threads no longer turns
  `@Async` methods into synchronous calls on the request thread.

---

## [0.22.0] - 2026-06-06

### Fixed

- **NUT-04 v1 mint-quote responses** — `MintQuoteTask`,
  `MintQuoteStatusTask`, and `VoucherMintQuoteTask` now emit `amount`,
  `unit`, and `state` on the mint-quote response. Modern wallets
  (cashu-ts `>= 4.x`) normalize every response and threw
  `AmountError: Unsupported amount input type` on the legacy v0 shape,
  blocking all client-side minting (imani spec 041). `state` is derived
  from the durable `mint_quote.lifecycle_state` (with a payment-flag
  fallback when no quote repository is wired); the deprecated `paid`
  boolean is still emitted for v0 consumers. Relative `expiry` is passed
  through unchanged — the client normalizes relative-vs-absolute itself.

### Changed

- Bumped `cashu-lib` `0.17.0` → `0.18.0` for the NUT-04 v1
  `PostMintQuoteResponse` fields (`amount`/`unit`/`state`).

## [0.21.0] - 2026-06-05

### Added

- **Spec 041 Phase 1 — bare `GET /v1/keys` NUT-01 listing.** Aggregates per-keyset
  NUT-02 lookups so cashu-ts 4.x's `Wallet.loadMint()` bootstrap call resolves
  cleanly. Prior versions only exposed the NUT-02 two-step pattern
  (`/v1/keysets` + `/v1/keys/{id}`), which broke any client using cashu-ts as the
  high-level wallet surface. See `imani-apps/packages/client-mint/CASHU_TS_API.md`
  §5 Finding 1 for the integration trace.
- **Spec 041 Phase 0 REQ-MINT-3 — strict NUT-04 quote expiry.** `MintTask` now
  computes `createdAt + getPaymentExpiry()` via the new `Gateway.getCreatedAt`
  port (payment-adapter 0.13.0) and throws a deterministic `quote_expired` error
  when the call lands past that instant. Falls through permissively when the
  gateway returns null `createdAt` (backward compatibility for rows persisted
  before the column was added). Phase 0 sign-off issued 2026-06-05 against
  staging running this code — see
  `imani-apps/specs/041-client-side-voucher-minting/contracts/abandoned-mint-recovery.contract.md`.

### Changed

- Updated `payment-adapter` to `0.13.0` (picks up the new `Gateway.getCreatedAt`
  port and the `GatewayQuote.createdAt` JPA column required by the expiry
  enforcement above).
- **Spec 041 T001 — CORS configuration for the public NUT surface.** Added a
  `CorsConfigurationSource` bean to `SecurityConfig` that allows GET/POST/OPTIONS
  on `/v1/**` and `/webhook/**` from origins configured via
  `cashu.mint.cors.allowed-origins` (env var
  `CASHU_MINT_CORS_ALLOWED_ORIGINS`, comma-separated). Falls back to wildcard
  when unset (safe for the unauthenticated public NUT surface; operators MUST
  set the env var to their wallet origin(s) on production). Required for
  browser-side cashu-ts to reach the mint at all.

---

## [0.20.0] - 2026-05-29

### Added

- **Spec 035 — voucher provenance lookup for the wallet's partial-spend
  display correction.** A cashu V4 voucher token's embedded
  `SignedVoucher.face_value` is frozen at original issuance and does not
  reflect partial spends. To let a receiving wallet correct the display
  for a partial-spend portion, the mint now exposes the original
  sat-denominated proof sum so the wallet can compute
  `derived = round(current_token_amount × face_value / original_token_amount)`.
  - **New REST endpoint** `GET /v1/vouchers/{voucherId}/provenance`
    returning `{ voucherId, faceValue, unit, originalTokenAmount,
    issuanceRatio, lifecycleState }`. `issuanceRatio` is computed
    strictly as `face_value / original_token_amount` (never from a
    current proof sum, which would reconstruct the frozen original).
    Returns `null` for both `originalTokenAmount` and `issuanceRatio`
    on legacy rows issued before this release; the wallet's source-chain
    resolution already accepts null and falls back to the embedded
    face_value path.
  - **New `voucher_quote.original_token_amount BIGINT NULL` column**
    (Flyway `V20260601_007`). Captured at voucher issuance time as
    `sum(blindedMessages)` in `MintTask`, written atomically with the
    `ISSUING → ISSUED` CAS via the new
    `VoucherQuoteRepository.recordIssuance(quoteId, originalTokenAmount)`
    port method. No backfill from `voucher_issuance.outputs_hash` —
    null cleanly means "legacy / unavailable" and is the documented
    fallback.

### Changed

- **`VoucherQuoteRepository` port** gains
  `int recordIssuance(String quoteId, long originalTokenAmount)`. Adapters
  must implement; in-memory test fixtures already get a no-op via the
  default `originalTokenAmount()` method on the `VoucherQuote` interface.
- **`MintTask`** voucher branch now calls `recordIssuance` instead of a
  bare `casLifecycle(ISSUING → ISSUED)`, so the lifecycle close and the
  amount capture commit together in a single UPDATE (no half-state with
  ISSUED but NULL amount, which would silently mis-flag the row as
  pre-migration legacy).

### Documentation

- `docs/explanations/voucher-data-record.md` adds a row for
  `original_token_amount` to keep the disclosure-doc schema-contract
  test green.

---

## [0.19.5] - 2026-05-25

### Fixed

- **PR #331 review follow-up.** `PaymentWebhookController`:
  - A missing/empty request body now returns `400 "Missing notification
    payload"` (a client mistake) instead of `401` — the signature validator
    is no longer consulted for an absent body, restoring the documented
    `400 invalid input` vs `401 auth failure` distinction.
  - The deserialize-failure log no longer includes the Jackson exception
    message (which can embed untrusted payload snippets, e.g. a preimage);
    it logs only the exception type at WARN, with the full stack trace at
    DEBUG.

---

## [0.19.4] - 2026-05-25

### Fixed

- **Spec 008 — conform the mint to the payment-adapter→mint webhook
  contract.** Closes the High finding in the 2026-05-24 backend token
  integrity review: a real adapter webhook could not be processed even
  with a shared HMAC secret.
  - `PaymentNotification` now maps the adapter's snake_case wire names
    (`quote_id`, `payment_method`, `receipt_id`, `paid_at`) via
    `@JsonProperty` (+ `@JsonAlias` for camelCase back-compat), adds a
    `unit` field, and `@JsonIgnoreProperties(ignoreUnknown = true)`.
    Previously a real payload deserialized `quoteId`/`paymentMethod`/
    `paidAt` as null and was rejected with `400 "Missing quoteId"`.
  - `WebhookSignatureValidator` now HMACs the EXACT raw request body
    bytes instead of a re-serialised DTO (the camelCase re-serialisation
    never matched the adapter's snake_case payload).
    `PaymentWebhookController` reads `@RequestBody byte[]` and authenticates
    over those bytes before deserialising.
  - Adds a golden-payload compatibility test + a real-validator end-to-end
    controller test. No adapter change; no schema change.

---

## [0.19.3] - 2026-05-25

### Fixed

- **PR #330 review follow-up — deterministic output errors now return a
  clean 4xx (not 500), and a null output no longer NPEs.** Spec 007 made
  `validateDenominations` throw typed `ErrorResponse` JSON, but
  `CashuController#handleCashuError` only mapped an allowlist of codes to
  `400`, so the new codes (and the pre-existing voucher
  `mint_amount_mismatch`) still surfaced as `internal_error`/500.
  - Extended the controller's code → HTTP status mapping to include
    `invalid_output_amount`, `invalid_denominations`, `missing_keyset_id`,
    `mint_request_missing_outputs`, `mint_request_contains_null_output`,
    and `mint_amount_mismatch` → `400`.
  - Added an up-front null-output guard in `MintTask.doExecute` (a
    null-safe loop, before any amount-sum/hash stream) so a `null` output
    returns `mint_request_contains_null_output` (4xx) instead of NPE-ing
    into a 500 in the amount-sum stream.
  - Tightened the spec-007 ITs to assert `is4xxClientError()` and added a
    null-output regression case.

---

## [0.19.2] - 2026-05-24

### Fixed

- **Spec 007 — validate deterministic output shape before the issuance
  lifecycle CAS.** Closes the High finding in the 2026-05-24 backend token
  integrity review: `MintTask` advanced the quote into `ISSUING` before
  running deterministic output validation, so a malformed-but-amount-summing
  request (e.g. `[11, -1]` for a 10-sat quote, or a non-canonical split)
  consumed a paid/funded quote into `ISSUING` and stranded it there.
  - Regular path: `validateDenominations` now runs immediately before the
    `PAID → ISSUING` CAS (gated on `lifecycleState == PAID`); for an
    already-advanced quote the CAS still yields `quote_already_issued` /
    `issuance_in_progress`. `quote_not_found` / `amount_mismatch` precedence
    is unchanged.
  - Voucher path: the `FUNDED → ISSUING` CAS is moved out of
    `resolveVoucherFunding` into `advanceVoucherToIssuing`, called only after
    the face-value output-sum check.
  - `validateDenominations` now throws typed `ErrorResponse` JSON, so a
    deterministic client error surfaces as a clean 4xx
    (`invalid_output_amount` / `invalid_denominations` / `missing_keyset_id`
    / `mint_request_missing_outputs` / `mint_request_contains_null_output`)
    instead of being mapped to `internal_error`/500.

---

## [0.19.1] - 2026-05-24

### Fixed

- **PR #329 review follow-up — preserve the FR-014 IOU-attempt alert on
  policy-denied paths.** `cashu_mint_voucher_iou_issued_total` is now
  incremented for **every** `MERCHANT_IOU` issuance attempt (before the
  policy gate), restoring the "regardless of policy" semantics existing
  dashboards/runbooks rely on. Under `DENY` the attempt still throws
  `iou_not_permitted` and additionally increments
  `cashu_mint_voucher_iou_denied_total`. Previously the issued counter
  fired only on the `ALLOW` path, blinding monitoring exactly in the
  denied scenario.

---

## [0.19.0] - 2026-05-24

### Security

- **Spec 006 — Enforce a reliable value-backing invariant for voucher issuance.**
  Closes the second Critical finding in the 2026-05-24 backend token
  integrity review: the mint signed full **face-value** Cashu for the
  `customer_paid` voucher variant while only the **fee** (`charged_amount`)
  was backed, and nothing verified the funding amount/unit. The
  customer-paid path was also operationally broken — a real voucher
  payment webhook was misclassified as `orphan`.
  - `MintTask` gains a fail-closed `enforceFaceValueBacking` gate that runs
    **before** the `FUNDED → ISSUING` CAS and before signing. The funding
    attached to the quote must cover the face value in the quote's unit:
    `CUSTOMER_PAYMENT` → `face_value_not_backed` (a fee payment can never
    back face value); `MERCHANT_DEBIT` → requires `amount >= face_value`
    and matching unit; `MERCHANT_IOU` → `iou_not_permitted` unless
    `cashu.mint.voucher.iou-policy == ALLOW`, then must also cover face
    value. A denied quote stays `FUNDED` and never signs.
  - `QuoteStatusUpdater` now falls back to `voucher_quote` on a `mint_quote`
    miss: a voucher payment with `amount == charged_amount` is classified
    `accepted` (was `orphan`) so the `CUSTOMER_PAYMENT` funding resolver can
    bind it; wrong amount → `amount_mismatch`.

### Added

- **FR-006 IOU policy enforcement (Medium finding).** `cashu.mint.voucher.iou-policy`
  was installed into `MintIntegrityContext` but never read; it is now
  enforced at issuance (`iou_not_permitted` when `DENY`).
- New typed errors `face_value_not_backed`, `iou_not_permitted`.
- Metrics `cashu_mint_voucher_face_value_not_backed_total`,
  `cashu_mint_voucher_iou_denied_total`.

---

## [0.18.1] - 2026-05-24

### Fixed

- **PR #328 review follow-ups (spec 005 fail-closed path).**
  - Refund-failure recovery (Codex P1): if `refundForSaga` throws while
    releasing a partial hold, the saga is now left in `PROOFS_HELD` (not
    forced to `FAILED`) so `MeltSagaReconciler.sweepStaleProofsHeld` can
    retry the refund — forcing `FAILED` would strand the proofs in
    `PENDING` with no automatic recovery.
  - Consistent terminal error (Copilot): every bind-failure path
    (normalize error, vault exception, partial bind) now throws the
    `proofs_not_bound` terminal error rather than leaking
    `melt_proof_pending_error` / the raw vault cause.
  - Renamed `buildNormalisedProofEntities` / `normalisedProofs` to the
    American spelling used elsewhere in the codebase.
  - Tests assert the `proofs_not_bound` code on the vault-exception paths
    (unit + IT) and add a refund-failure-leaves-PROOFS_HELD case.

### Changed

- Updated cashu-vault 0.9.0 → 0.9.1 (insert-or-claim hardening).

---

## [0.18.0] - 2026-05-24

### Security

- **Spec 005 — Enforce durable melt-saga proof holds before external payment.**
  Closes the highest-priority finding in the 2026-05-24 backend token
  integrity review: the melt saga recorded `PROOFS_HELD` but the durable
  hold was not actually enforced before `lightningPaymentPort.pay`, so a
  melt could pay externally against zero or partially-bound proof rows.
  - `MeltTask` now performs a single atomic insert-or-claim per melt via
    the new `ProofVaultService.insertOrClaimForSaga`, after Y-normalising
    every proof through `ProofEntity.fromProof` (the canonical identity
    already used on the burn/SPENT path). This eliminates both the
    insert-then-claim no-op and the raw-secret/Y duplicate-row class.
  - **Fail-closed:** if not every submitted proof is durably bound (or the
    vault call throws), the saga releases any partial hold via
    `refundForSaga`, transitions `PROOFS_HELD → FAILED`, caches a terminal
    `proofs_not_bound` error, increments
    `cashu_mint_melt_proofs_not_bound_total`, and throws **before**
    `lightningPaymentPort.pay` is reached.
  - Requires cashu-vault 0.9.0 (additive `insertOrClaimForSaga` primitive).

### Added

- New client-facing error code `proofs_not_bound` (messages.properties).
- Metric `cashu_mint_melt_proofs_not_bound_total`.
- `MeltSagaProofsNotBoundIT` (4 cases: partial / zero / vault-exception bind
  all abort before payment; happy path proves bind-before-pay via the saga
  ledger) plus 3 new `MeltSagaStateMachineTest` unit cases.

### Deprecated

- `ProofVaultService.markPendingForSaga` — superseded by
  `insertOrClaimForSaga`. Retained as the underlying primitive used by the
  saga reconciler.

### Changed

- Updated cashu-vault 0.8.2 → 0.9.0.

---

## [0.17.0] - 2026-05-24

### Added

- **Spec 003 — Voucher Quote Durability and Funding-Source Binding** (FR-001
  through FR-013). Every voucher proof is now traceable to a durable
  `voucher_funding` row; the "skip payment check" loophole is closed.
  - Four new PostgreSQL tables in `cashu-mint-jpa`: `voucher_funding`
    (Envers-audited, JOINED-inheritance parent of `customer_payment_funding`
    / `merchant_debit_funding` / `merchant_iou_funding`), `voucher_quote`
    (Envers-audited, CAS-transitioned `UNFUNDED → FUNDED → ISSUING →
    ISSUED`), `voucher_issuance` (append-only ledger), and
    `voucher_idempotency_key` (DB-backed `Idempotency-Key` cache).
  - 8 protocol ports in `cashu-mint-protocol/.../proto/` for the voucher
    domain plus `VoucherFundingResolver` strategy + default impl that scans
    `webhook_event` for an `accepted` event and lazily creates a
    `CustomerPaymentFunding` row (idempotent on
    `(provider, provider_event_id)`).
  - `VoucherMintQuoteTask` persists a `voucher_quote` row at quote-creation
    time. `MintTask`'s voucher branch loads the durable record, runs the
    resolver, rejects with `funding_required` when no funding row resolves,
    then CAS-advances through `FUNDED → ISSUING → ISSUED` after signing.
  - `VoucherQuoteRegistry` demoted to a read-through cache; the durable
    repository is the source of truth.
  - REST hardening: `/v1/vouchers/**` now requires `ADMIN` role (FR-007),
    per-principal Caffeine rate-limit + 429 + `Retry-After` +
    `cashu_mint_voucher_rate_limit_breach_total` counter (FR-008),
    DB-backed idempotency replay + 409 tamper detection (FR-009), and a
    scheduled TTL prune for the idempotency cache.
  - Operator queries embedded as Javadoc on `VoucherIssuanceJpaRepository`
    (SC-001 orphan-issuance query + IOU liability dashboard).
- **Spec 004 — Voucher Data Minimisation and Customer-Identity Custody**
  (FR-001 through FR-019, plus Constitution Principle VII ratification
  `1.1.0 → 1.2.0`). Closes the data-custody gap spec 003 introduced — the
  mint no longer stores raw customer / merchant npubs.
  - **Hash at rest**: HMAC-SHA-256 via `javax.crypto.Mac`; salt from env
    `CASHU_MINT_VOUCHER_IDENTITY_SALT` (≥ 32 bytes; boot fails closed
    otherwise). `IdentityHashConverter` applied via `@Convert` on every
    identity column on `voucher_quote`, `customer_payment_funding`,
    `merchant_debit_funding`, and `merchant_iou_funding`.
  - **Anonymous purchases**: `customer_id` nullable; null short-circuits
    the hasher so no enumerable hash-of-empty placeholder appears.
  - **Boot-time backfill**: `VoucherIdentityBackfillService` paginated
    (1000-row chunks, configurable), idempotent via
    `customer_id !~ '^[0-9a-f]{64}$'` filter. Updates live + Envers
    `_aud` rows in one transaction via `VoucherIdentityBackfillBatch`.
    `VoucherBackfillHealthIndicator` keeps `/actuator/health/readiness`
    DOWN until every required table has `completed_at IS NOT NULL`.
  - **Retention purge**: `VoucherIdentityRetentionPurgeService` daily
    `@Scheduled` (cron from `cashu.mint.voucher.identity-purge-cron`,
    default `0 0 3 * * *`). Nullifies identity columns on terminal-state
    rows past the retention boundary (default 90 days). Records audit row
    in `voucher_quote_purge_log` for the "purged" vs "anonymous"
    distinction.
  - **Idempotency cache scrub**: `IdentityFieldScrubber` walks JSON
    response bodies before they hit `voucher_idempotency_key.
    response_body_json`; hashes identity field values so a DB dump of
    the cache contains zero raw npubs.
  - **Forensic lookup** (FR-009): `POST /admin/voucher/forensic/
    customer-purchases` + `/merchant-purchases`. Operator submits raw
    npub; mint hashes internally; returns matching voucher_quotes. Salt
    never leaves the mint.
  - **Three new Grafana dashboards**: `voucher-liability-overview`,
    `voucher-token-integrity` (orphan-issuance gauge — SC-001 made
    glanceable), `voucher-iou-liability`. All run via a dedicated
    `cashu_mint_grafana_ro` PostgreSQL role with **column-level
    `GRANT SELECT` that excludes `customer_id` + `merchant_id`** —
    defence in depth on top of dashboard JSON review.
    `GrafanaRolePermissionIT` proves the DB-layer enforcement.
  - Customer-facing disclosure document at `docs/explanations/voucher-
    data-record.md`; CI test `DisclosureDocSchemaContractTest` fails the
    build on schema-vs-doc drift.

### Changed

- Bumped `cashu-mint` aggregator and all internal modules from `0.16.0`
  to `0.17.0`. No breaking API changes; additive only.
- `MintIntegrityContext` service-locator gained `.installVoucher()` +
  `.identityHasher()` accessors, extending the pattern from specs
  001/002/003 to the spec-004 surface.

### Security

- Customer + merchant npubs are now stored as HMAC-SHA-256 digests on
  every voucher-related table. A DB dump no longer reveals raw
  identifiers. Salt rotation in v1 is forward-only and explicitly
  accepts loss of pre-rotation forensic-lookup capability (see
  `specs/004-voucher-data-minimisation/quickstart.md` § 7).
- Grafana DB role `cashu_mint_grafana_ro` is provisioned with
  column-level grants that EXCLUDE identity columns — an ad-hoc
  Grafana query against `customer_id` fails at the PostgreSQL layer
  with `permission denied for column customer_id`.

### Fixed (PR #324 review round)

- `IdentityHashConverter` is now idempotent on already-hashed values
  (regex `^[0-9a-f]{64}$`). A JPA load → merge no longer double-hashes
  the stored HMAC, which would have broken every forensic lookup.
- `VoucherIdentityBackfillService` injects `IdentityHasher` directly
  instead of pulling from the static `MintIntegrityContext` at
  `@PostConstruct`; removes init-order race that could silently no-op
  the backfill.
- Per-batch backfill execution extracted to `VoucherIdentityBackfillBatch`
  so the `@Transactional` boundary is honoured by the Spring proxy
  (live + `_aud` UPDATEs commit together per research R9).
- `VoucherIdentityRetentionPurgeService` audit-table UPDATE now scopes
  by the live row's `lifecycle_state`; previously could purge `_aud`
  revisions of still-active UNFUNDED / FUNDED rows.
- Grafana datasource + Flyway placeholder now read the same env var
  (`CASHU_MINT_GRAFANA_RO_PASSWORD`) — previously the two diverged and
  silently broke dashboards.
- Prometheus backward-compat aliases switched from
  `metric_relabel_configs` (which REWROTE singular → plural and dropped
  the singular) to recording rules in `alerts.yml` (which CREATE the
  plural alongside the live singular).
- Alertmanager spec=004 routes moved BEFORE the severity catch-alls so
  the first-match-wins router actually reaches the voucher-pagerduty /
  voucher-slack-* receivers.
- `quickstart.md` § 7: replaced fictional `rotate-identity-salt`
  command with the actual v1 forward-only rotation runbook.

### Migration notes

- The new voucher / data-minimisation behaviour is gated on
  `cashu.mint.jpa.enabled=true`. Existing deployments are unaffected
  until the flag is flipped.
- When flipping the flag, populate the new env vars per
  `specs/004-voucher-data-minimisation/quickstart.md` § 1–2: at minimum
  `CASHU_MINT_VOUCHER_IDENTITY_SALT` (≥ 32 bytes) and
  `CASHU_MINT_GRAFANA_RO_PASSWORD`. Boot fails closed when the salt is
  unset or too short.
- First boot after flag flip runs the identity backfill (idempotent,
  paginated). `/actuator/health/readiness` stays DOWN until every
  identity column completes; mint stays out of the load balancer
  rotation during that window.

---

## [0.16.0] - 2026-05-23

### Added

- **Spec 001 — Mint Quote Amount Binding and Webhook Integrity** (NUT-04 /
  NUT-19 / FR-001 through FR-014). All P1 + P2 user stories closed. New
  behaviour is opt-in behind `cashu.mint.jpa.enabled=true` (defaults to
  `false`); existing deployments are unaffected until the flag is flipped.
- New `cashu-mint-jpa` Maven module with three append-only / audit-tracked
  tables in PostgreSQL: `mint_quote` (Envers-audited), `issuance_record`,
  and `webhook_event`. Ships its own Flyway migrations under
  `db/migration/spec001/` and an opt-in Spring autoconfig
  (`MintJpaAutoConfiguration`) that provides DataSource, EntityManagerFactory,
  and TransactionManager beans from `cashu.mint.jpa.datasource.*` properties.
- US1: `MintTask` now binds every NUT-04 issuance to the durable quote
  amount (FR-001), CASes `PAID → ISSUING → ISSUED` (FR-002 / FR-011),
  cross-checks the gateway via `Gateway.getAmount(quoteId)` (FR-010), and
  emits `cashu_mint_amount_mismatch_total{path="mint"}` /
  `cashu_mint_quote_cross_check_failures_total{path="mint"}` counters.
- US2: `QuoteStatusUpdater.record(...)` rewrite — webhook `PENDING → PAID`
  transitions now require matching `(amount, unit, payment_method)` and a
  previously-unseen `(provider, provider_event_id)` (FR-005 / FR-006). Each
  delivery persists exactly one append-only `webhook_event` row with the
  resolved outcome (`accepted` / `amount_mismatch` / `unit_mismatch` /
  `method_mismatch` / `duplicate` / `tamper` / `expired` / `noop` /
  `orphan` / `unsigned_rejected` / `signature_invalid`, FR-008).
  `cashu_mint_webhook_event_total{outcome=...}` counter emitted on every
  classification.
- US3: NUT-19 idempotent replay — a retry against an `ISSUED` quote with
  the same blinded outputs returns the previously signed promises; with
  different outputs, `quote_already_issued`. Bounded-backoff polling
  (50/100/200/400/800 ms) when a concurrent writer is mid-`ISSUING`.
  `cashu_mint_idempotent_replay_total{path="mint"}` counter on every replay.
- FR-007 — `WebhookSecretStartupValidator` fails the Spring context in any
  non-`local` profile when `cashu.mint.webhook.shared-secret` is unset.
  Existing `webhook.secret` continues to work; the canonical key going
  forward is `cashu.mint.webhook.shared-secret`.
- FR-009 — `LongArithmeticArchTest` (ArchUnit) fails the build if any
  amount-bearing field in `cashu-mint-protocol`'s `tasks/` or `ports/`
  packages is typed `int`/`Integer` instead of `long`. Caught and widened
  one existing regression (`MintQuoteTask.amount`).
- New port interfaces in `cashu-mint-protocol/.../proto/ports/`:
  `MintQuote` / `MintQuoteRepository` / `IssuanceRecord` /
  `IssuanceRecordRepository` / `WebhookEvent` / `WebhookEventRepository` /
  `ProviderIdentifier` / `MintIntegrityContext`.
- `OutputsHash` utility — SHA-256 over sorted `(amount, keyset_id, B_)`
  tuples — backs the NUT-19 idempotent-replay equality check.
- New docs: `docs/how-to/configure-webhook-integrity.md` covers the
  mandatory-secret contract, provider key, feature flag, outcome → HTTP
  status table, and operator reconciliation SQL.
- `NutAdvertisementContractTest` pins the advertised NUT set in
  `mint.yaml` against a documented test-backed allow list
  ({4, 5, 7, 8, 9, 10, 11, 12, 17}); the test fails if a NUT is added or
  removed without updating the inventory.
- `MintQuoteJpaRepository` class-level Javadoc ships two operator
  reconciliation queries (FR-001 + FR-005 daily invariants).

### Changed

- Webhook idempotency is now durably keyed by
  `(provider, provider_event_id)` per FR-006.
  `PaymentNotification#getIdempotencyKey()` (the legacy `paymentMethod:quoteId`
  cache key) is `@Deprecated`. The cache-only path is retained as a
  fallback when the durable repositories are absent (unit-test contexts).
- `QuoteStatusUpdater` Caffeine cache is now a read-through accelerator
  only; write decisions go through the durable repositories when wired.
- `WebhookSignatureValidator` removed the silent skip-if-blank branch
  (FR-007). A missing secret now fails validation rather than passing
  through.
- `cashu-mint-admin` modules aligned to 0.16.0 to catch up the stale
  0.14.2 parent reference that lingered after the 0.15.0 bump.

### Fixed

- `MintJpaAutoConfiguration` now wires its own `DataSource`,
  `EntityManagerFactory`, `JpaTransactionManager`, and Flyway runner —
  earlier scaffolding declared `@EnableJpaRepositories` without a
  DataSource bean, so the `cashu.mint.jpa.enabled=true` path was dead
  code that failed at context startup.
- `MintQuoteJpaRepository#casLifecycle` is now `@Transactional` —
  earlier the modifying JPQL CAS threw
  `InvalidDataAccessApiUsageException` when called from non-transactional
  code paths.
- `QuoteStatusUpdater#record` is now `@Transactional` to wrap the
  combined `webhook_event` insert + `mint_quote` CAS.
- Spec 001 Flyway migrations moved to `db/migration/spec001/` to avoid
  V1 collisions with `payment-adapter-model` and `cashu-vault-jpa` on the
  classpath.
- All hash columns (`request_hash`, `outputs_hash`, `signature_digest`)
  switched from `CHAR(64)` to `VARCHAR(64)` so Hibernate's schema validator
  doesn't reject the mapping.
- `flyway-database-postgresql` pinned in `dependencyManagement` to match
  `${flyway.version}` (11.2.0); Spring Boot's transitive resolution was
  pulling 11.7.2 and crashing the Flyway runner.

### Tests

- `cashu-mint-protocol`: 171 → 190 cases (+19) — `OutputsHashTest`,
  `MintTaskAmountValidationTest`, `IssuingConcurrencyTest`,
  `LongArithmeticArchTest`, `NutAdvertisementContractTest`, plus
  extended `MintQuoteTaskTest`.
- `cashu-mint-webhook`: 38 → 49 cases (+11) — `QuoteStatusUpdaterDurableTest`
  exercises the full outcome matrix with mocked durable repositories.
- `cashu-mint-rest-it`: 5 new spec-001 IT classes (18 cases) under
  Testcontainers Postgres 16-alpine — `FlywayMigrationIT`,
  `WebhookSignatureBootIT`, `WebhookAmountBindingIT`,
  `MintQuoteAmountBindingIT`, `MintQuoteConcurrencyIT`.

---

## [0.15.0] - 2026-05-22

### Added

- `/v1/info` now advertises NUT-11 (P2PK spending conditions). The mint
  already verified P2PK secrets at the wire (`P2PKSpendingCondition` wired
  into `VerifyProofsTask:128`) but the info response silently omitted the
  entry, so clients that gate P2PK use on `/v1/info` — including
  `imani-gateway-core`'s spec-029 in-person delivery saga branch — would
  refuse the path despite swap requests working. The new entry is a simple
  `supported: true` (no per-method config), matching NUT-09 / NUT-10 / NUT-12.

---

## [0.14.2] - 2026-05-12

### Fixed

- `CheckStateTask` (NUT-07 `/v1/checkstate`) was passing the already-computed hash-to-curve point `Y` back through `DefaultProofVaultService.retrieveProof()`, which calls `SecretUtil.toYFromString` (= `hash_to_curve`) again. The second hash produced a point that no stored proof was keyed under, so every checkstate response was a false `UNSPENT` regardless of the proof's actual state. Wallets relying on NUT-07 as a pre-spend oracle were flying blind, only learning that proofs were SPENT/PENDING when the subsequent swap rejected them.
- `VoucherSpendingCondition.verify()` rejected proofs with `verify_proof_already_used_error` for *any* existing `ProofEntity`, not just terminal `STATE_SPENT`. This blocked legitimate saga retries when the same proofs were still in `STATE_PENDING` from a prior in-flight attempt. The condition now only rejects on `STATE_SPENT`, mirroring `InvalidateProofsTask.storeAndInvalidateIdempotent()`'s idempotent recovery path.
- `RSSSpendingCondition.verify()` had the same PENDING-treated-as-terminal bug; same fix applied.

### Added

- `ProofVaultService.retrieveProofByY(yHex)` — new lookup method for callers that already hold the hash-to-curve point Y (NUT-07 wire shape). Skips the hash step that `retrieveProof(secret)` applies for raw-secret callers. `CheckStateTask` now uses this method.
- `DefaultProofVaultServiceTest` — regression coverage proving `SecretUtil.toYFromString` is not idempotent on Y, and pinning the contract for both lookup methods.

---

## [0.14.1] - 2026-02-18

### Added

- HashiCorp Vault service and initialization scripts to `docker-compose.dev.yml` and `docker-compose.prod.yml`
- Admin module: user guide tutorial and transactional outbox explanation documentation

### Changed

- Updated `payment-adapter` dependency to 0.10.0
- Updated `cashu-vault` dependency to 0.7.0

### Fixed

- Admin module: made lifecycle history append idempotent for outbox at-least-once redelivery
- Admin module: added missing Flyway migration scripts and made outbox handler fail-fast
- Admin module: corrected test migration paths to `db/migration/admin/`
- Protocol: resolved DLEQ non-determinism and duplicate storage key issues in tests

---

## [0.14.0] - 2026-02-17

### Added

- `CONTRIBUTING.md` contributor guide with branch naming, commit conventions, and PR process
- `docs/reference/glossary.md` — Cashu and ecash terminology (~35 terms)
- `docs/reference/error-codes.md` — complete REST API error code reference with causes and resolutions
- `docs/how-to/deploy-production.md` — production deployment checklist (TLS, secrets, monitoring, backups)
- `docs/how-to/develop-gateway-adapter.md` — guide for implementing custom payment gateway adapters
- `docs/tutorials/websocket-client-example.md` — step-by-step WebSocket client tutorial with wscat and JavaScript
- `docs/how-to/troubleshoot-common-issues.md` — consolidated troubleshooting guide
- `docs/explanations/virtual-thread-adoption.md` — consolidated virtual thread adoption narrative
- Admin module: vault provisioning saga with compensation logic
- Admin module: active mint checks by unit during lifecycle create and resume operations
- Admin module: integration and E2E tests for vault provisioning saga

### Changed

- Expanded `docs/reference/rest-api.md` with WebSocket endpoint documentation and error response section
- Expanded `docs/reference/nuts.md` from link list to table with implementation classes and status
- Added NUT-12 (DLEQ proofs) and NUT-17 (WebSocket subscriptions) to `docs/explanations/architecture-and-nuts.md`
- Merged `voucher-mint-quote-overview.md` TL;DR into `voucher-mint-quote-percentage.md`
- Updated `README.md` with admin module section, contributing link, and Diataxis documentation index
- Updated `TESTING.md` troubleshooting section to cross-reference new troubleshooting guide
- Archived `docs/loom/` files to `docs/archive/loom/` with header notes pointing to consolidated doc
- Admin module: updated Flyway migration location and Jackson serialization settings
- Admin module: enhanced error handling and configuration
- Updated `mint-admin-web` vite config to use environment variable for API proxy target
- Added Byte Buddy dependency and excluded `slf4j-simple` from `cashu-lib-common`
- Converted `cashu-mint-admin` from git submodule to regular directory

### Removed

- Deleted `docs/explanations/voucher-mint-quote-overview.md` (merged into percentage doc)
- Removed `docs/loom/` directory (archived to `docs/archive/loom/`)

---

## [0.13.0] - 2026-02-16

### Added

- Integrated `cashu-mint-admin` as a git submodule with CLI, REST API, web UI, and test modules
- Added `mint-admin-web` and `mint-admin-tests` to the Maven reactor
- New documentation: getting started tutorial, architecture overview, NUT implementation guide, E2E test guide, environment variables reference
- Admin module documentation: CLI command reference, REST API reference, configuration reference, architecture explanation

### Changed

- Enhanced error handling and updated database configuration for PostgreSQL
- Overhauled `docs/` directory — expanded skeleton files, removed misplaced content, consolidated trivial docs
- Updated CLAUDE.md docs file count from 21 to 32

### Removed

- Removed misplaced imani-bridge files (`SECURE_CODING.md`, `security-implementation-plan.md`)
- Removed unfilled `baseline-metrics.md` template
- Consolidated `java-version.md`, `license.md`, and `disclaimer.md` into parent docs

---

## [0.12.2] - 2026-02-03

### Changed

- Updated `cashu-vault` dependency to 0.6.0

### Added

- Added security section to README documenting Oracle Java Secure Coding Guidelines compliance

---

## [0.12.1] - 2026-02-02

### Changed

- **QuoteStatusUpdater**: Migrated from count-based to weight-based eviction using `maximumWeight()` and custom weigher for accurate memory management
- **QuoteStatusUpdater**: Added `recordStats()` and Micrometer metrics integration (`CaffeineCacheMetrics.monitor()`) for cache observability
- **VoucherQuoteRegistry**: Replaced unbounded `ConcurrentHashMap` with Caffeine cache (24h TTL, 10k max entries) to prevent memory leaks

### Improved

- **Collection Capacity Optimization**: Added initial capacity to HashMap/ArrayList constructors across protocol tasks to reduce resizing overhead:
  - `MintProtocolUtil.createLightningAddressRequest()`: HashMap capacity 3
  - `MintTask.execute()`: HashMap capacity 4
  - `SwapTask.execute()`: ArrayList capacity matching input size
  - `RestoreSignaturesTask`: ArrayList capacities for outputs/signatures
  - `P2PKSpendingCondition`: Estimated ArrayList capacity
- **SwapTask**: Optimized double stream iteration to single pass for voucher proof detection

### Fixed

- Removed unused import for `DBMintVault` in `MintProtocolUtil`
- Added `results/` directory to `.gitignore`

---

## [0.12.0] - 2026-02-02

### Security

- Completed Oracle Java Secure Coding Guidelines remediation tasks: final NUT/utility classes with private constructors, SHA-256 key derivation for lock keys, unmodifiable MintInfo nuts map, sanitized exception messages, webhook input validation, configurable swap/mint limits, WebSocket subscription limits, and NUT security Javadoc

### Changed

- Moved the Java secure coding audit report into the `audits/` directory

### Fixed

- Aligned `VerifyProofsTaskTest` voucher secret FQCN assertion with the current `nut18` package

---

## [0.11.1] - 2026-01-31

### Fixed

- **NUT-17 Info Endpoint**: Fixed NUT-17 WebSocket configuration not appearing in `/v1/info` response
  - Spring's `@ConfigurationProperties` binding could not correctly handle complex nested list structures bound to `Object` type fields
  - Added direct YAML loading via SnakeYAML for NUT-17 configuration
  - Controller now explicitly serializes nuts map using getter to ensure dynamically loaded NUT-17 is included
  - Added unit test to verify NUT-17 support in mint info

---

## [0.11.0] - 2026-01-28

### Added

- **NUT-17 WebSocket Subscriptions**: Real-time notifications for proof and quote state changes
  - WebSocket endpoint at `/v1/ws` with JSON-RPC 2.0 protocol
  - `SubscriptionManager` for session tracking and efficient pub/sub with indexing
  - `Nut17WebSocketHandler` for JSON-RPC message handling (subscribe/unsubscribe)
  - `ProofStatePublisher` for broadcasting proof state transitions (UNSPENT → PENDING → SPENT)
  - `QuoteStatePublisher` for broadcasting quote state transitions (UNPAID → PAID → ISSUED)
  - `Nut17EventPublisher` service for publishing events from controllers
  - Spring ApplicationEvent integration for decoupled event propagation
  - Configurable via `cashu.websocket.enabled` property
  - Security warning logged at startup when wildcard origins (`*`) used in production

- **NUT-17 Protocol Implementation**: `NUT17.java` static utility class
  - Subscription parameter validation
  - Subscription ID generation
  - JSON-RPC response and notification factory methods
  - Filter ID extraction utilities

- **NUT-17 Integration Tests**: `Nut17WebSocketIT` with comprehensive test coverage
  - WebSocket connection establishment
  - Subscribe/unsubscribe command handling
  - Current state notification on subscription
  - Proof state change notifications
  - Multiple subscriber notification delivery

- **NUT-17 Unit Tests**: Event publisher test coverage
  - `ProofStatePublisherTest` for proof state event handling
  - `QuoteStatePublisherTest` for quote state event handling
  - `Nut17EventPublisherTest` for event emission via ApplicationEventPublisher

- **Virtual Thread Guidelines**: Comprehensive `CLAUDE.md` documentation
  - Decision table for when to use Virtual Threads
  - Code patterns for parallel I/O with CompletableFuture
  - Anti-patterns to avoid (synchronized on I/O, platform thread pools)
  - Configuration reference for VT-related components

### Changed

- Updated `mint.yaml` to advertise NUT-17 WebSocket subscription support
- Updated cashu-lib dependency from 0.13.1 to 0.14.0 (includes NUT-17 DTOs)
- Updated cashu-wallet dependency from 0.6.1 to 0.6.3
- `SubscriptionManager.sendCurrentState()` now uses Virtual Threads for parallel I/O
  - Parallel vault queries for proof state lookups
  - Parallel gateway queries for mint/melt quote state lookups
  - Improves performance when subscribers watch multiple items
- `WebSocketConfig` allowed-origins split now handles whitespace around commas
- Removed redundant `@ConditionalOnProperty` from `WebSocketConfig` bean method
- NUT-17 quote state payloads now enriched with additional fields from quote lookups
  - Mint quote notifications include `amount`, `request`, and `expiry`
  - Melt quote notifications include `amount` and `expiry`

### Fixed

- Race condition in `SubscriptionManager.subscribe()` using atomic `compute()` operation

---

## [0.10.2] - 2026-01-28

### Added

- Request tracing and logging for `/swap` endpoint with correlation IDs for easier debugging
- Idempotent proof handling in `InvalidateProofsTask` to safely handle duplicate invalidation requests

### Changed

- Updated cashu-lib dependency from 0.13.0 to 0.13.1

---

## [0.10.1] - 2026-01-26

### Changed

- Aligned all child module versions to 0.10.1 (previously at 0.9.0)
- Updated cashu-vault dependency to 0.5.0

---

## [0.10.0] - 2026-01-26

### Added

- **Proof Locking for Double-Spend Prevention**: `SwapTask` now uses per-proof locking via `ProofLockManager`
  - Serializes concurrent swap requests for the same proofs
  - Prevents double-spend attacks at the application level
  - Allows parallel swapping of different proof sets
  - Complements database-level unique constraints in cashu-vault

- **Concurrency Tests for SwapTask**: Comprehensive test suite in `SwapTaskConcurrencyTest`
  - Tests serialization of same-proof swaps
  - Tests parallel execution of different-proof swaps
  - Tests double-spend prevention behavior
  - Tests partial overlap handling

### Changed

- Updated nostr-java dependency from 1.2.1 to 1.3.0
- Updated cashu-lib dependency from 0.12.0 to 0.13.0
- Updated cashu-vault dependency from 0.4.6 to 0.5.0

### Fixed

- **Webhook Cache TTL**: Added TTL-based cache eviction to `QuoteStatusUpdater`
  - Prevents unbounded memory growth from completed payments
  - Configurable via `webhook.cache.ttl` property

---

## [0.9.0] - 2026-01-25

### Added

- **Webhook-Based Payment Notifications**: New `cashu-mint-webhook` module for push-based payment status
  - `PaymentWebhookController` receives payment events at `/webhook/payment`
  - `QuoteStatusUpdater` maintains in-memory cache for instant payment lookups
  - `WebhookSignatureValidator` validates HMAC-SHA256 signatures from `X-Webhook-Signature` header
  - `PaymentNotification` DTO with idempotency key generation for deduplication
  - Health endpoint at `/webhook/health` with cache statistics

- **PaymentStatusChecker Interface**: New abstraction in `cashu-mint-protocol` for payment verification
  - `isPaid(quoteId)` for instant cache lookup
  - `getPreimage(quoteId)` to retrieve payment proof
  - `markConsumed(quoteId)` for cleanup after successful minting
  - `MintTask` checks webhook cache first, falls back to gateway polling

- **Comprehensive Webhook Tests**: Unit and integration test coverage
  - `PaymentWebhookControllerTest`, `QuoteStatusUpdaterTest`, `WebhookSignatureValidatorTest`
  - `PaymentWebhookIT`, `PaymentWebhookE2EIT` for end-to-end testing

- **Payment Webhook Documentation**: `docs/explanations/payment-webhook-architecture.md`
  - Architecture comparison (polling vs push-based)
  - Configuration and benefits

### Changed

- `MintTask` now checks `PaymentStatusChecker` before polling gateway, reducing latency for cached payments
- `cashu-mint-rest` depends on `cashu-mint-webhook` module

---

## [0.8.0] - 2026-01-23

### Added

- **Virtual Thread Support (Project Loom)**: Full implementation for Java 21+ runtime optimization
  - Enable via `spring.threads.virtual.enabled=true` (default) or `SPRING_THREADS_VIRTUAL_ENABLED` env var
  - Virtual threads handle all request processing and async tasks
  - Per-quote locking with `QuoteLockManager` for parallel quote processing
  - Per-proof locking with `ProofLockManager` for parallel melt operations
  - Double-mint detection in `DefaultSignatureVaultService` as safety net

- **Lock Observability**: Prometheus metrics for lock contention monitoring
  - `cashu_mint_lock_wait_seconds` - time spent waiting to acquire locks
  - `cashu_mint_lock_hold_seconds` - time spent holding locks
  - `cashu_mint_lock_active` - current number of held locks
  - `LockMetrics`, `MicrometerLockMetricsAdapter` in observability module

- **Virtual Threads Grafana Dashboard**: New `cashu-mint-virtual-threads.json` dashboard
  - Lock contention panels (wait time, hold time, active locks)
  - JVM thread metrics (live threads, thread states)
  - Tomcat connection pool monitoring

- **Gateway Client Optimization**: JDK HttpClient with virtual thread executor
  - `GatewayClientConfiguration` provides `gatewayRestTemplate` bean
  - Configurable timeouts: `GATEWAY_CLIENT_CONNECT_TIMEOUT`, `GATEWAY_CLIENT_READ_TIMEOUT`

- **VT Operational Runbook**: `docs/runbooks/virtual-thread-issues.md`
  - Diagnosis and resolution for lock contention, pinning, memory leaks
  - Rollback procedure and escalation path

- **Load Testing Infrastructure**: k6 scripts and baseline metrics capture
  - `scripts/load-test-mint.js` for performance testing
  - Heap exhaustion testing for VT workloads

### Changed

- Updated cashu-lib dependency from 0.11.1 to 0.12.0
- Updated cashu-wallet dependency from 0.4.4 to 0.5.0
- Tomcat thread pool reduced to 50 max threads (VTs handle concurrency)
- Tomcat connection limits set to 2000 max-connections (primary VT concurrency limit)
- `AsyncConfig` configures virtual thread executor for `@Async` tasks

### Deprecated

- `ThreadUtil.MINT_MELT_LOCK` - replaced by `QuoteLockManager.lockQuote()` for per-quote locking

### Fixed

- Voucher arbitrary denominations now work correctly for minting and swaps

---

## [0.7.3] - 2026-01-21

### Changed

- Renamed payment-gateway dependencies to payment-adapter (0.6.0)
- Updated docker-compose service names from gateway to adapter

### Fixed

- Voucher issuance responses now include a generated `cashuA` token so REST clients and Nostr integration tests receive non-null voucher tokens.
- Integration test configuration now uses the token-enriching voucher service to keep Nostr voucher flows aligned with the REST behavior.

---

## [0.7.2] - 2026-01-15

### Changed

- Updated payment-gateway dependency to 0.5.0

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
