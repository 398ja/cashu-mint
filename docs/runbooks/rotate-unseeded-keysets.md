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

Run this from the imani-deploy checkout on the Vault host, as one script (`sh step1.sh`), **not**
pasted into a shell inside the container. The root token is read from `.env.staging` *now* and
reaches the container only on stdin through `docker exec -i`, the same way `vault-kv.py` does it.
It never goes in `-e`, argv or shell history. The seed is generated inside the container
(the `hashicorp/vault` image has **no `openssl`**, so `$(openssl rand -hex 32)` would silently write
an empty seed), checked to be 64 hex before anything is written, and passed to `vault kv put` on
stdin (argv is visible in `ps`). Nothing is printed except status lines.

```sh
# Run on the Vault host from the imani-deploy checkout. Prints status lines only.
set -eu
VC=${VC:-hashicorp-vault-server}
ENV_FILE=${ENV_FILE:-.env.staging}
ROOT=$(grep -m1 '^VAULT_HASHI_ROOT_TOKEN=' "$ENV_FILE" | cut -d= -f2- | tr -d "\"'")
[ -n "$ROOT" ] || { echo "no VAULT_HASHI_ROOT_TOKEN in $ENV_FILE" >&2; exit 1; }
printf '%s\n' "$ROOT" | docker exec -i "$VC" sh -c '
set -eu
IFS= read -r VAULT_TOKEN; export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
P=cashu/seeds/mint-admin/keyset-master-seed
# 0. Refuse to start if the path was ever written (any version, even deleted).
if vault read cashu/metadata/seeds/mint-admin/keyset-master-seed >/dev/null 2>&1; then
  echo "seed path already exists: STOP, see Recovery" >&2; exit 1; fi
# 1. Generate first (the image has no openssl). Abort before any write unless 64 hex.
S=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d " \n")
[ ${#S} -eq 64 ] || { echo "seed generation failed, nothing written" >&2; exit 1; }
# 2. Create-only policy for exactly the seed path.
printf "%s\n" "path \"cashu/data/seeds/mint-admin/keyset-master-seed\" { capabilities = [\"create\"] }" \
  | vault policy write mint-admin-seed-create - >/dev/null
# 3. Short-lived create-only child token; keep its accessor for the revocation check.
T=$(vault token create -policy=mint-admin-seed-create -ttl=15m -use-limit=5 -field=token)
ACC=$(printf %s "$T" | vault write -field=accessor auth/token/lookup token=-)
cleanup() {
  vault token revoke -accessor "$ACC" >/dev/null 2>&1 || true
  vault policy delete mint-admin-seed-create >/dev/null 2>&1 || true
}
# 4. CAS-0 write under the child token, value on stdin (never argv).
if ! printf "{\"seed\":\"%s\"}" "$S" | VAULT_TOKEN=$T vault kv put -cas=0 "$P" - >/dev/null; then
  unset S T; cleanup; echo "write refused: token revoked, policy deleted" >&2; exit 1; fi
unset S
echo "seed written (CAS 0)"
# 5. Revoke, prove it is gone (accessor lookup must fail), delete the policy.
VAULT_TOKEN=$T vault token revoke -self >/dev/null; unset T
if vault token lookup -accessor "$ACC" >/dev/null 2>&1; then echo "child token still valid" >&2; exit 1; fi
echo "child token revoked (accessor lookup refused)"
vault policy delete mint-admin-seed-create >/dev/null
echo "policy mint-admin-seed-create deleted"
'
```

The script refuses to start if the seed path was ever written, aborts before any write if
generation fails, and on a refused write it revokes the child token and deletes the policy itself.
The revocation check uses the token **accessor** (`vault token lookup -accessor`), which succeeds
before revoke and fails after, so it cannot pass vacuously. Deleting `mint-admin-seed-create` is
part of the script, not optional.

**STOP POINT (required before the re-baseline).** Check with the root token: length and version only,
never the value.

```sh
# STOP POINT. Root token on stdin. Prints only a length and a version, never the value.
set -eu
VC=${VC:-hashicorp-vault-server}
ENV_FILE=${ENV_FILE:-.env.staging}
ROOT=$(grep -m1 '^VAULT_HASHI_ROOT_TOKEN=' "$ENV_FILE" | cut -d= -f2- | tr -d "\"'")
printf '%s\n' "$ROOT" | docker exec -i "$VC" sh -c '
set -eu
IFS= read -r VAULT_TOKEN; export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
V=$(vault kv get -field=seed cashu/seeds/mint-admin/keyset-master-seed)
echo "seed_length=$(printf %s "$V" | wc -c)"; unset V
echo "current_version=$(vault read -field=current_version cashu/metadata/seeds/mint-admin/keyset-master-seed)"
'
```

Expected: `seed_length=64` and `current_version=1`. Anything else (`0` means an empty seed, a version
above 1 means more than one write): **stop**, go to *Recovery: bad seed written* below, and do not
re-baseline or deploy.

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

Check: `secrets` = pre-seed count **+ 1** (the pre-seed baseline count from precondition 2, plus 1; do not reuse an old number), `key_secrets` unchanged,
`secrets_without_private_key` +1, `digest` differs from the pre-seed one. Record in the
ticket and in the #135 evidence: date, digest prefix, `secrets`, `key_secrets`, and "re-baselined
after cashu-mint#545 seed write". From here on every `--expect-digest` (custody restore checks,
the #135 runbook) uses the **new** digest.

**Then deploy the fixed mint-admin (staging), with a stop point and a rollback.**

```sh
# imani-deploy checkout on the staging host.
C="docker compose -f docker-compose.staging.yml --env-file .env.staging"
# a. Freeze mint creation: no POST /admin/lifecycle/mints until step 2 is checked.
# b. PROVISIONING must be 0 (precondition 5), immediately before the deploy.
# c. Record the running image as the rollback target (0.43.1 today):
docker inspect mint-admin --format '{{.Config.Image}} {{.Image}}'   # write both into the ticket
PREV_IMAGE=docker.398ja.xyz/staging/cashu-mint-admin-rest@sha256:<previous digest from c>
# d. Deploy the fixed release pinned by digest, recreating only mint-admin
#    (--no-deps: the mint, Vault and vault-keys-guard are not recreated):
MINT_ADMIN_REST_IMAGE=docker.398ja.xyz/staging/cashu-mint-admin-rest@sha256:<fixed release digest> \
  $C up -d --no-deps mint-admin
# e. STOP POINT: within 2 minutes it must log the seed fingerprint and stay up.
docker logs mint-admin 2>&1 | grep 'Keyset master seed loaded from Vault'
docker inspect mint-admin --format '{{.State.Status}} restarts={{.RestartCount}}'   # running restarts=0
```

Check: `Keyset master seed loaded from Vault ... (fingerprint <16 hex>)`. Record the fingerprint in
the ticket; it is a one-way tag, not the seed. If it refuses to start, the message names the reason
(missing, unreadable, malformed or weak seed, or a 403 from a stale token). Never bypass it
(no `ADMIN_KEYSET_SEED_GENERATE_IF_ABSENT`, no second seed).

**Rollback of the deploy** (mint-admin fails closed, or any doubt at the stop point):

```sh
MINT_ADMIN_REST_IMAGE=$PREV_IMAGE $C up -d --no-deps mint-admin
docker inspect mint-admin --format '{{.Config.Image}} {{.State.Status}}'   # previous digest, running
```

- The seed stays in Vault. It is harmless: 0.43.1 never reads it, and the #135 baseline that
  includes it stays valid.
- While rolled back: **no mint provisioning and no rotation** (0.43.1 derives unseeded keys). Keep
  mint creation frozen until the fixed release is running.
- Fix the named cause (a 403 means recreate mint-admin with the current root token; a seed
  problem means the stop point above was skipped, go to *Recovery: bad seed written*), then
  redo the deploy from step c.

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
  redeeming as **inputs**, per NUT-02. They no longer sign: a request whose **outputs** name an
  archived keyset is refused with `12002 keyset_inactive`
  (`ValidateTransactionTask.rejectInactiveOutputKeysets`).
- **Client impact.** Fresh requests are fine: imani-wallet reads `/v1/keysets` on each call
  (`src/lib/mintTransport.ts:118` `activeKeysetId`) and gateway-core
  `DefaultMintSwapService.getActiveKeysetId` does the same, so they pick up the new keyset once the
  mint's 60 s keyset cache has expired. Not fine: outputs blinded **before** the rotation and sent
  after it. imani-wallet resumes a pending receive with its stored `keysetId` and outputs
  (`src/lib/receiveSwap.ts:220-231`, for NUT-19 byte-identical replay; also `recoverInterruptedSwaps`,
  line 305ff) and has **no handling for 12002** (imani-wallet 0.22.0, `2cd19fb`: 0 hits), so such a
  receive fails on every retry. The intent is kept, so no value is lost, but it is stuck until the
  wallet re-blinds: **follow-up imani-wallet#248** (re-blind on 12002 when no signatures were
  returned). cashu-wallet `KeysetDirectory.refresh()` has no caller in the library; the
  consuming app decides when to refresh.
- **Mitigation.** Rotate at low traffic. Just before, check for in-flight work and let it drain
  (no pending receive intents or swaps on the operator's own wallets; no quotes in progress on
  the mint). After the rotation, a `12002` for an output keyset that was active a minute ago is the
  expected refusal, not an incident. Until #248 ships, a receive caught across the rotation stays
  pending with its value kept, and completes once the wallet runs a build with #248; record any
  such case in the ticket.
- **One mint at a time:** rotate one mint, run the per-mint check below, then stop before the next.
  Staging is expected to have a single mint; say so in the ticket if it does.

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

- Step 1: a **good** unused seed is harmless and is left in place (also when the deploy is
  rolled back). A **bad** seed (empty, wrong length, more than one version) must be removed
  before anything uses it: see *Recovery: bad seed written*.
- Deploy: roll back to the recorded previous mint-admin digest (0.43.1), see step 1.
- Step 2: rotating back would reinstate a forgeable keyset, so do not. If the new keyset
  misbehaves, rotate again (a fresh rotation id yields a fresh seeded keyset). The old keyset
  keeps redeeming throughout.

## Recovery: bad seed written (DESTRUCTIVE)

Only when the step-1 STOP POINT shows `seed_length` other than 64 or `current_version` other than 1.
This deletes every version and the metadata of exactly one path. It is safe **only if no keyset
was derived from that seed yet**, so check first, and stop and escalate if any check fails:

1. The fixed mint-admin has **never** run against this Vault since the write: `docker inspect
   mint-admin --format '{{.Config.Image}}'` is still the previous release, and its log has no
   `Keyset master seed loaded from Vault` line.
2. No mint was provisioned or rotated since the write (`GET /admin/lifecycle/mints` and the
   keysets per mint match the inventory; PROVISIONING = 0).

Then, root token on stdin as in step 1, delete exactly that path and redo step 1 and its STOP POINT:

```sh
set -eu
ROOT=$(grep -m1 '^VAULT_HASHI_ROOT_TOKEN=' .env.staging | cut -d= -f2- | tr -d "\"'")
printf '%s\n' "$ROOT" | docker exec -i hashicorp-vault-server sh -c '
set -eu
IFS= read -r VAULT_TOKEN; export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
vault kv metadata delete cashu/seeds/mint-admin/keyset-master-seed
vault policy delete mint-admin-seed-create >/dev/null 2>&1 || true
'
```

Record the deletion in the ticket and in the #135 evidence (the fingerprint must be re-baselined
again after the new write).

## Local gate (before step 2 on staging)

Run the mint-admin e2e suite (`SeededKeysetFlowE2EIT` provisions a new mint, mints, swaps and
melts through phoenixd-mock, and checks a proof forged with the unseeded key is refused), then
the imani-deploy terminal and licence e2e on your own `GATE_PROJECT`. Tear the gate down after.
