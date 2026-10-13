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
   The dev Vault was retired by imani-deploy#190 (master `c6008ee` or later); confirm #135 itself is
   closed and record the current #135 fingerprint (digest prefix and secret count) as the
   *pre-seed baseline* in the ticket.
3. **Vault access, as it really is today** (see cashu-mint#550 for the least-privilege fix):
   - **Staging:** there is no AppRole for mint-admin. It authenticates with the Vault **root
     token** (`VAULT_HASHI_ROOT_TOKEN` in `.env.staging`, server-mode `hashicorp-vault-server`,
     mount `cashu`). The root token was **rotated on 2026-10-13**: use whatever `.env.staging`
     holds *now*, never a token copied into notes, shell history or an earlier ticket. Every
     client (mint-admin included) must have been recreated after that rotation; a stale token
     makes mint-admin fail with 403 at startup (fail closed, visible), never fall back.
   - **Production:** `docker-compose.prod.yml` gives `cashu-mint-admin-rest` **no Vault
     configuration at all**, so the fixed mint-admin will not start there. The existing
     `cashu-vault` AppRole belongs to cashu-vault-jpa, and its `seeds/*` grant (create, read,
     update) is not least privilege. **Prod-launch blocker: cashu-mint#550** (imani-deploy#60).
   - **Step-1 token:** do not use the root token for the write. Mint a **short-lived child token**
     limited to creating that one path, use it once, revoke it (step 1 below).
4. No real Lightning backend or real fiat rail is wired to the environment (staging must be
   `phoenixd-mock`). If one is, stop and escalate: forged ecash there is real money.
5. **No mint is mid-provisioning:** `GET /admin/lifecycle/mints?state=PROVISIONING` lists none
   (equivalently, read-only on the mint-admin DB: `SELECT count(*) FROM mints WHERE
   lifecycle_state = 'PROVISIONING'` = **0**) right before the deploy and in the inventory. A mint caught in
   PROVISIONING across the upgrade, with a keyset row written under the old derivation, would have
   that partial unseeded keyset left in place and be marked PROVISIONED. If the count is not 0,
   let those mints finish on the old release (or decommission them) before deploying.

## Inventory (read-only, counts only)

Record, without printing any key material or private-key-derived value:

- the keysets per mint, per unit: id, `state` (SIGNING / ARCHIVED), `createdAt`, fee
  (`GET /admin/lifecycle/mints/{id}/keysets`);
- which of them are **unseeded**: every keyset whose `createdAt` is before the release in
  precondition 1, plus every keyset seeded by `VaultPreloadSeeder` from a JSON fixture
  (the staging active keyset comes from `imani-deploy/config/test/preload-test-data.json`);
- the outstanding balance per keyset, from the mint's ledger (issued minus redeemed), so the
  retirement deadline can be judged by what is still in circulation.
- the number of mints in `PROVISIONING` (must be 0, precondition 5).

Write the inventory to the change ticket as counts and keyset ids only.

## Step 1: create the seed (once per environment)

```bash
# On the Vault host, inside the Vault container. Never echo a token or the seed. MOUNT=cashu.
# 1. A create-only policy for exactly the seed path (no read, no update):
vault policy write mint-admin-seed-create - <<'HCL'
path "cashu/data/seeds/mint-admin/keyset-master-seed" { capabilities = ["create"] }
HCL
# 2. A short-lived child token with only that policy. Authenticate this one command with the
#    CURRENT root token read from .env.staging (rotated 2026-10-13); do not reuse an old value.
#    The built-in `default` policy stays attached: it grants no secrets, only what the CLI needs
#    (KV v2 mount preflight, token self-lookup/revoke).
STEP1_TOKEN=$(vault token create -policy=mint-admin-seed-create -ttl=15m -use-limit=5 \
  -field=token)
# 3. Create the seed with CAS 0, under the child token only.
VAULT_TOKEN=$STEP1_TOKEN vault kv put -cas=0 "$MOUNT/seeds/mint-admin/keyset-master-seed" \
  seed="$(openssl rand -hex 32)" >/dev/null
# 4. Revoke it at once; unset it.
VAULT_TOKEN=$STEP1_TOKEN vault token revoke -self; unset STEP1_TOKEN
```

- `-cas=0` succeeds only if the path has never been written, so a seed can never be replaced
  by mistake. **Never** rotate or overwrite the seed: replacing it changes the keys of any
  keyset still being provisioned or rotated, and an outbox redelivery would then refuse to
  write (cashu-mint#544's read-before-write) or provision a different keyset.
- Optional hardening (part of cashu-mint#550): `vault kv metadata put -cas-required=true
  "$MOUNT/seeds/mint-admin/keyset-master-seed"`.
- Do not set `ADMIN_KEYSET_SEED_GENERATE_IF_ABSENT` outside disposable stacks.
- **Backup and custody.** The #135 gpg custody export (`vault-kv.py export`) walks the whole
  `cashu/` mount, so from now on it **contains the seed**. That is the backup this step needs:
  the export and its passphrase are now custody material for the seed as well as for every key,
  under the same controls. Losing the seed does not lose existing keys (they are stored in
  Vault), but it makes provisioning of an in-flight message non-reproducible. Take a fresh
  export after the re-baseline below.
- Confirm the step-1 token is gone (`vault token lookup <accessor>` fails) and the policy
  `mint-admin-seed-create` can be deleted (`vault policy delete mint-admin-seed-create`).

**Re-baseline the #135 fingerprint (staging) right after the write.** `vault-kv.py fingerprint`
digests every secret under `cashu/` (a non-key secret counts by the SHA-256 of its whole value),
so the seed **moves the digest and adds one secret**. Comparing against the pre-seed baseline
would now report `MISMATCH`; that is expected and is not a key change. Compute and record the
new baseline:

```bash
# imani-deploy checkout at master c6008ee or later, on the staging host. The script reads the
# token from the env file itself (current VAULT_HASHI_ROOT_TOKEN); nothing is printed but counts
# and a 16-hex digest prefix.
python3 scripts/vault-server/vault-kv.py fingerprint --container <hashicorp-vault-server container> \
  --env-file .env.staging --out /dev/shm/fp-after-seed.json
```

Check: `secrets` = pre-seed count **+ 1** (staging today: 57 -> 58), `key_secrets` unchanged,
`secrets_without_private_key` +1, `digest` differs from the pre-seed one. Record in the
ticket and in the #135 evidence: date, digest prefix, `secrets`, `key_secrets`, and "re-baselined
after cashu-mint#545 seed write". From here on every `--expect-digest` (custody restore checks,
the #135 runbook) uses the **new** digest.

**Then deploy the fixed mint-admin. Check:** it starts and logs
`Keyset master seed loaded from Vault ... (fingerprint <16 hex>)`. Record the fingerprint in the
ticket; it is a one-way tag, not the seed. If mint-admin refuses to start, the message names
the reason (missing, unreadable, malformed or weak seed, or a 403 from a stale token); fix that,
never bypass it. Re-run the PROVISIONING count (precondition 5) immediately before the deploy.

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
keyset and the one it replaced; then **wait more than 60 s** (the mint caches keysets for
`mint.keyset-cache.ttl-seconds`, default 60, so it may still advertise the old keyset as active
just after the rotation) before checking that `/v1/keysets` shows the new id `active: true`
(66 hex chars, `01` prefix) and the old id `active: false`; a test mint of a few sats and a swap
of those proofs succeed on the new keyset. For staging, run the local gate first (below),
including a rehearsal of "register the fixture-seeded mint in mint-admin" with a copy of
`imani-deploy/config/test/preload-test-data.json`: a wrong unit or ladder there derives a
different keyset.

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
> advertising those keysets: **cashu-mint#551**, its own approval. Until it ships, step 4 is
> blocked and the window in step 3 is open-ended, which is acceptable for staging on
> phoenixd-mock only.

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
