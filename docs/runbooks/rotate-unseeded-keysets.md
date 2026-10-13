# Runbook: rotate keysets provisioned without a secret seed (cashu-mint#545)

> **Confidential.** cashu-mint is a private repository. Keep this runbook, its outputs and any
> derived values out of public repos, issues, PRs and chats until every unseeded keyset is
> retired for redemption.

This runbook is **separate from the code change**. Shipping the fix makes every *new* keyset
safe; it does not touch existing ones, by design. Every keyset provisioned before the fix
(and every keyset seeded from a committed preload fixture) has private keys anyone holding the
mint id can recompute, so each one must be rotated out and then retired. Each step below is its
own approved operation. Nothing here runs automatically.

## Preconditions (all must hold before step 1)

1. The fix is released. **Do not deploy it yet:** the new mint-admin refuses to start without a
   seed, so step 1 (create the seed) comes first, then the deploy, then step 2.
2. **Staging only:** the imani-deploy#135 Vault cutover is complete and its 3-day run-in has
   passed, and the dev Vault is retired. Until then the derivability of the 22 archived
   "memory-only" keys is the migration's safety net, so do not start.
3. You have a Vault token that can write `<mount>/data/seeds/*` for the duration of step 1 only.
   mint-admin's AppRole policy already covers `seeds/*` (read, create, update).
4. No real Lightning backend or real fiat rail is wired to the environment (staging must be
   `phoenixd-mock`). If one is, stop and escalate: forged ecash there is real money.

## Inventory (read-only, counts only)

Record, without printing any key material or private-key-derived value:

- the keysets per mint, per unit: id, `state` (SIGNING / ARCHIVED), `createdAt`, fee
  (`GET /admin/lifecycle/mints/{id}/keysets`);
- which of them are **unseeded**: every keyset whose `createdAt` is before the release in
  precondition 1, plus every keyset seeded by `VaultPreloadSeeder` from a JSON fixture
  (the staging active keyset comes from `imani-deploy/config/test/preload-test-data.json`);
- the outstanding balance per keyset, from the mint's ledger (issued minus redeemed), so the
  retirement deadline can be judged by what is still in circulation.

Write the inventory to the change ticket as counts and keyset ids only.

## Step 1: create the seed (once per environment)

```bash
# On the Vault host, with the step-1 token exported as VAULT_TOKEN. Never echo the value.
vault kv put -cas=0 "$MOUNT/seeds/mint-admin/keyset-master-seed" seed="$(openssl rand -hex 32)"
```

- `-cas=0` succeeds only if the path has never been written, so a seed can never be replaced
  by mistake. **Never** rotate or overwrite the seed: replacing it changes the keys of any
  keyset still being provisioned or rotated, and an outbox redelivery would then refuse to
  write (cashu-mint#544's read-before-write) or provision a different keyset.
- Do not set `ADMIN_KEYSET_SEED_GENERATE_IF_ABSENT` outside disposable stacks.
- Back the seed up with the Vault backup/unseal material, under the same controls. Losing it
  does not lose existing keys (they are stored in Vault), but it makes provisioning of an
  in-flight message non-reproducible.
- Revoke the step-1 token.

**Then deploy the fixed mint-admin. Check:** it starts and logs
`Keyset master seed loaded from Vault ... (fingerprint <16 hex>)`. Record the fingerprint in the
ticket; it is a one-way tag, not the seed. If mint-admin refuses to start, the message names
the reason (missing, unreadable, malformed or weak seed); fix that, never bypass it.

## Step 2: rotate every unseeded active keyset

For each mint and unit whose SIGNING keyset is unseeded:

- The mint must be registered in mint-admin to be rotated through it. Staging's active keyset
  was seeded by the mint's `VaultPreloadSeeder` from a fixture, not provisioned by mint-admin;
  register that mint id in mint-admin first (as `KeyRotationE2EIT` does), with the same unit
  and denomination ladder, and confirm it reaches `PROVISIONED` without writing keys (the
  existing active keyset is left in place).

```
POST /admin/operations/mints/{mintId}/keys/rotate   {"reason": "cashu-mint#545 seeded rotation", "durationMinutes": 5}
```

- The rotation derives a new keyset from the seed, archives the old one first and then
  provisions the new one (the vault allows one active keyset per unit), and reinstates the old
  one if provisioning fails.
- Archived keysets stay advertised by `/v1/keysets` with `active: false` and keep verifying and
  redeeming, per NUT-02. Wallets that refresh `/v1/keysets` before transacting (imani-wallet,
  cashu-wallet `KeysetDirectory`, gateway-core `DefaultMintSwapService`) pick up the new active
  keyset with no change.

**Check per mint:** the control reaches `KEY_ROTATION_COMPLETED`; its outcome names the new
keyset and the one it replaced; `/v1/keysets` shows the new id `active: true` (66 hex chars,
`01` prefix) and the old id `active: false`; a test mint of a few sats and a swap of those
proofs succeed on the new keyset. For staging, run the local gate first (below).

## Step 3: migration window

- Announce the window to wallet operators: proofs from the old keysets remain redeemable until
  the deadline, and swapping them now moves the value onto the new keyset.
- Watch, per old keyset: redemptions per day and the outstanding balance. A rising balance on
  an ARCHIVED keyset is not possible from honest wallets (archived keysets do not sign), so
  any increase in proofs redeemed beyond the inventoried balance is a forgery signal: escalate.
- Proposed window: **staging 14 days** (test money only). **Production** never has unseeded
  keysets: launch only with seeded keysets and never preload a fixture into production.

## Step 4: retire the unseeded keysets for redemption (the dated deadline)

After the deadline, the old keysets must stop **redeeming** as well as signing, because a
forged proof is indistinguishable from a real one at redemption time and shortening the window
is the only mitigation.

> **Gap to close before step 4:** the mint has no "retired for redemption" keyset state today.
> ARCHIVED keysets redeem indefinitely (ADR-0004). Step 4 needs a follow-up change that adds a
> per-keyset redemption cutoff (refuse inputs from a listed keyset id after a date) and stops
> advertising those keysets. Track it as its own issue and approval. Until it ships, step 4 is
> blocked and the window in step 3 is open-ended.

## Step 5: clean up

- Remove committed fixtures that map to the retired keysets (this PR already removes the
  cashu-mint copies; `imani-deploy/config/test/preload-test-data.json` is handled in
  imani-deploy as its own change, after step 2, because staging's active keyset is seeded
  from it until then).
- Add "keysets seeded (cashu-mint#545)" to the production launch checklist (imani-deploy#60).
- Close cashu-mint#545 only when steps 1 to 4 are done for staging.

## Rollback

- Step 1 has no rollback and needs none: an unused seed is harmless.
- Step 2: rotating back would reinstate a forgeable keyset, so do not. If the new keyset
  misbehaves, rotate again (a fresh rotation id yields a fresh seeded keyset). The old keyset
  keeps redeeming throughout.

## Local gate (before step 2 on staging)

Run the mint-admin e2e suite (`SeededKeysetFlowE2EIT` provisions a new mint, mints, swaps and
melts through phoenixd-mock, and checks a proof forged with the unseeded key is refused), then
the imani-deploy terminal and licence e2e on your own `GATE_PROJECT`. Tear the gate down after.
