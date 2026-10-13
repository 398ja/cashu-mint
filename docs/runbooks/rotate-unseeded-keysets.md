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
(the `hashicorp/vault` image has **no `openssl`**, so generating the seed with openssl inside it
would silently write an empty seed), checked to be 64 hex before anything is written, and passed to `vault kv put` on
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
ACC=
# Any exit after this point (set -e included) revokes the child token and deletes the policy.
cleanup() {
  if [ -n "$ACC" ]; then vault token revoke -accessor "$ACC" >/dev/null 2>&1 || true; fi
  vault policy delete mint-admin-seed-create >/dev/null 2>&1 || true
}
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
trap cleanup EXIT
ACC=$(printf %s "$T" | vault write -field=accessor auth/token/lookup token=-)
# 4. CAS-0 write under the child token, value on stdin (never argv).
if ! printf "{\"seed\":\"%s\"}" "$S" | VAULT_TOKEN=$T vault kv put -cas=0 "$P" - >/dev/null; then
  unset S T; echo "write refused: token revoked, policy deleted" >&2; exit 1; fi
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
generation fails, and on **any** exit (a refused write, a failed revocation check, any `set -e`
stop) the `EXIT` trap revokes the child token by accessor and deletes the policy, so nothing is
left behind.
The revocation check uses the token **accessor** (`vault token lookup -accessor`), which succeeds
before revoke and fails after, so it cannot pass vacuously. Deleting `mint-admin-seed-create` is
part of the script, not optional.

Before step 1 and again after the fingerprint below, check the Vault container did not restart
or run out of memory (it runs with a 384m limit): `docker inspect hashicorp-vault-server
--format 'restarts={{.RestartCount}} oom={{.State.OOMKilled}}'` must stay the same, `oom=false`.

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
L=$(printf %s "$V" | wc -c | tr -d " "); unset V
N=$(vault read -field=current_version cashu/metadata/seeds/mint-admin/keyset-master-seed)
echo "seed_length=$L"; echo "current_version=$N"
[ "$L" = 64 ] && [ "$N" = 1 ] || { echo "STOP: expected 64 / 1, see Recovery" >&2; exit 1; }
echo "stop point passed"
'
```

Expected: `seed_length=64`, `current_version=1`, `stop point passed`, exit 0. Anything else
(the script then prints `STOP` and exits 1; `0` means an empty seed, a version
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
python3 scripts/vault-server/vault-kv.py fingerprint --container hashicorp-vault-server \
  --env-file .env.staging --out /dev/shm/fp-after-seed.json
```

Check: `secrets` = pre-seed count **+ 1** (the pre-seed baseline count from precondition 2, plus 1; do not reuse an old number), `key_secrets` unchanged,
`secrets_without_private_key` +1, `digest` differs from the pre-seed one. Record in the
ticket and in the #135 evidence: date, digest prefix, `secrets`, `key_secrets`, and "re-baselined
after cashu-mint#545 seed write". From here on every `--expect-digest` (custody restore checks,
the #135 runbook) uses the **new** digest.

**Then deploy the fixed mint-admin (staging), with a stop point and a rollback.**

Every command below is self-contained: it uses the full compose command (no alias) and either a
literal digest or a variable guarded with `${VAR:?}`, which aborts the command with
`VAR: set ...` instead of running with an empty value. This matters because compose reads
`image: ${MINT_ADMIN_REST_IMAGE:-...cashu-mint-admin-rest:latest}`: an **empty**
`MINT_ADMIN_REST_IMAGE` (even an empty shell variable, which overrides `.env.staging`) silently
deploys `:latest`.

Known rollback target, **0.43.1** (imani-deploy `build-refs.env`, redeploy 18, 2026-10-13):

```
docker.398ja.xyz/staging/cashu-mint-admin-rest@sha256:e70e1a3f4b6d186df6e483b3ffb1553005dd4c82624bedebfdf74deaf132f00c
```

a. Freeze mint creation: no `POST /admin/lifecycle/mints` until step 2 is checked.

b. PROVISIONING must be 0 (precondition 5), immediately before the deploy.

c. Cross-check that the running mint-admin is that 0.43.1 digest. This prints the **registry
   digest** (`RepoDigests`), which is pullable. `{{.Image}}` alone is the local image ID and
   cannot be pulled (`No such image: ...@sha256:<image id>`), so never use it for a rollback.

```sh
# imani-deploy checkout on the staging host.
docker image inspect "$(docker inspect -f '{{.Image}}' mint-admin)" --format '{{index .RepoDigests 0}}'
```

   It must print exactly the 0.43.1 line above. Write it into the ticket. If it prints anything
   else (or nothing), **stop**: the rollback target is not what `build-refs.env` says; resolve
   that first and use the printed registry digest as the rollback target in the ticket.

d. Back up `.env.staging` **outside the repository**, then deploy the fixed release pinned by digest, recreating only
   mint-admin (`--no-deps`: the mint, Vault and vault-keys-guard are not recreated). Set
   `FIXED_IMAGE` in **this** shell from the release ticket
   (`docker.398ja.xyz/staging/cashu-mint-admin-rest@sha256:` plus the 64-hex digest of the fixed
   release); the guard refuses to run if it is unset or empty.
   The backup goes to `~/env-backups/` (directory 0700, file 0600), the convention of imani-deploy's
   `rotate-staging-secrets-after-env-backup-leak.md`. Never write a backup inside the checkout:
   imani-deploy master's `.gitignore` covers `.env*-bak*`, `.env*.bak` and `.env*.bak.*` but **not**
   a name like `.env.staging.bak-<date>`, and a tracked env backup is exactly how imani-deploy#134
   leaked every staging credential.

```sh
install -d -m 700 ~/env-backups
install -m 600 .env.staging ~/env-backups/.env.staging.$(date -u +%Y%m%dT%H%M%SZ)
MINT_ADMIN_REST_IMAGE="${FIXED_IMAGE:?set FIXED_IMAGE to the fixed release digest}" \
  docker compose -f docker-compose.staging.yml --env-file .env.staging up -d --no-deps mint-admin
```

e. STOP POINT: within 2 minutes it must log the seed fingerprint and stay up.

```sh
docker logs mint-admin 2>&1 | grep 'Keyset master seed loaded from Vault'
docker inspect mint-admin --format '{{.State.Status}} restarts={{.RestartCount}}'   # running restarts=0
docker image inspect "$(docker inspect -f '{{.Image}}' mint-admin)" --format '{{index .RepoDigests 0}}'   # = FIXED_IMAGE
```

Check: `Keyset master seed loaded from Vault ... (fingerprint <16 hex>)`. Record the fingerprint in
the ticket; it is a one-way tag, not the seed. If it refuses to start, the message names the reason
(missing, unreadable, malformed or weak seed, or a 403 from a stale token). Never bypass it
(no `ADMIN_KEYSET_SEED_GENERATE_IF_ABSENT`, no second seed). **Ignore the command suggested in the
refusal message** (`seed="$(openssl rand -hex 32)"`): the seed is created only by step 1 and its
STOP POINT (the message is fixed in cashu-mint#550).

> **⚠ IRREVERSIBLE once e passes and anything is provisioned or rotated.** From the first mint
> provisioned or rotated by the fixed mint-admin, keys are derived from this seed, so the seed is
> **permanent**: it can never be replaced, and *Recovery: bad seed written* no longer applies.
> **Still undoable after this point:** the mint-admin deploy (rollback below, by literal digest) and
> the durable pin (step f). **Not undoable:** the seed itself and any keyset derived from it.

f. **Only after the stop point passes**, make the pin durable, so the next routine `up` by anyone
   does not fall back to `:latest`. Run f in a **fresh shell**: an exported `MINT_ADMIN_REST_IMAGE`
   in the operator's shell overrides the env file (an exported empty value gives `:latest`).
   Set `MINT_ADMIN_REST_IMAGE=<FIXED_IMAGE value>` in `.env.staging` **on the host and in the
   operator's local copy**, both backed up first to `~/env-backups/` exactly as in d (`install -m 600`,
   never inside the checkout) and kept byte-equal for that line: `grep '^MINT_ADMIN_REST_IMAGE='
   .env.staging` on each must print exactly `MINT_ADMIN_REST_IMAGE=` followed by the `FIXED_IMAGE`
   value, and nothing else. Then record it in imani-deploy `build-refs.env` (its own commit, next to the 0.43.1
   entry: version, digest, redeploy number, date, "cashu-mint#545 seeded keysets, mint-admin only").
   Check the durable pin resolves to the same image without recreating anything:

```sh
docker compose -f docker-compose.staging.yml --env-file .env.staging config --images | grep cashu-mint-admin-rest
```

   It must print exactly the `FIXED_IMAGE` value (the same line as e's third command), nothing else.

**Rollback of the deploy** (mint-admin fails closed, or any doubt at the stop point). The digest
is written out; nothing depends on a variable from an earlier shell.

```sh
# imani-deploy checkout on the staging host.
MINT_ADMIN_REST_IMAGE=docker.398ja.xyz/staging/cashu-mint-admin-rest@sha256:e70e1a3f4b6d186df6e483b3ffb1553005dd4c82624bedebfdf74deaf132f00c \
  docker compose -f docker-compose.staging.yml --env-file .env.staging up -d --no-deps mint-admin
docker image inspect "$(docker inspect -f '{{.Image}}' mint-admin)" --format '{{index .RepoDigests 0}}'   # the 0.43.1 digest
docker inspect mint-admin --format '{{.State.Status}} restarts={{.RestartCount}}'                         # running
```

(If step c printed a different registry digest and the ticket names it as the target, use that
literal digest in place of the 0.43.1 one.)

- If step f already ran, put the **0.43.1** digest back as `MINT_ADMIN_REST_IMAGE` in
  `.env.staging` (host and local, backed up first to `~/env-backups/` with `install -m 600`, kept equal) and record the rollback in
  `build-refs.env`, so a later `up` does not redeploy the fixed release.
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
  was seeded by the mint's `VaultPreloadSeeder` from a fixture, not provisioned by mint-admin,
  so register that mint id in mint-admin first. **Do not copy the body from `KeyRotationE2EIT`:**
  it registers an 11-amount ladder (1 to 1024), and the ladder registered here decides the
  *rotated* keyset. The staging fixture (`imani-deploy/config/test/preload-test-data.json`) has
  mint id `1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae`, unit `sat`, fee 0 and **24** amounts,
  1 to 8388608 (powers of two), which equals `VaultProvisioningOutboxHandler.DEFAULT_DENOMINATIONS`.
  The literal request (no secrets in it; authenticate with your own admin session):

```
POST /admin/lifecycle/mints
Cookie: cashu_admin_session=<your NAP admin session>     (needs the MINT_LIFECYCLE permission)
Content-Type: application/json

{
  "mintId": "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae",
  "metadata": { "displayName": "staging mint", "description": "cashu-mint#545 registration of the fixture-seeded mint", "tags": ["cashu-mint#545"] },
  "configuration": {
    "versionTag": "v1",
    "cashu.unit": "sat",
    "cashu.denominations": "1,2,4,8,16,32,64,128,256,512,1024,2048,4096,8192,16384,32768,65536,131072,262144,524288,1048576,2097152,4194304,8388608"
  }
}
```

  No fee key (fee 0 is the default). **Registration STOP POINT:** `GET /admin/lifecycle/mints/1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae`
  shows `lifecycleState` = `PROVISIONED`; the mint-admin log has
  `Mint 1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae already holds active keyset <id> for unit sat; leaving it in place`
  (`VaultProvisioningAdapter`), with `<id>` the inventoried active keyset id; and `/v1/keysets` on the
  mint is unchanged from the inventory (same ids, same `active` flags). Anything else: **stop**.
  **If the registration is wrong** (wrong unit or ladder, or keys were written): do **not** rotate.
  The registration alone has not changed the mint's keys, so nothing is lost by stopping; escalate
  rather than improvising a fix (retiring the record through `POST /admin/lifecycle/mints/{mintId}/retire`
  is not proven safe for a served mint).

> **⚠ IRREVERSIBLE: from the rotation request below.** Once the rotation runs, the old keyset is
> archived and must **never** be reinstated (rotating back would reinstate a forgeable keyset), and
> the seed is permanent (*Recovery: bad seed written* no longer applies). **Still undoable:** the
> mint-admin deploy (rollback by literal digest) and a bad *new* keyset (rotate again: a fresh
> rotation id yields a fresh seeded keyset). **Not undoable:** archiving the old keyset, and the
> seed. Old proofs keep redeeming throughout, so no wallet value is lost.

Rotation request (same session cookie, `Content-Type: application/json`):

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
  receive fails on every retry **within the same app session**. The intent is kept, so no value is
  lost. It clears at the **next app start**: the start-up sweep (`App.tsx`) runs
  `recoverInterruptedSwaps`, which uses NUT-09 restore (no keyset-active check, so no 12002); if the
  mint never signed, restore returns nothing, the intent is dropped and the next receive blinds
  fresh against the active keyset. If the mint did sign, the byte-identical replay is answered from
  the NUT-19 cache before validation, also without 12002. **Follow-up imani-wallet#248** (re-blind on
  12002 when no signatures were returned) removes the need for a restart. Today the only live
  caller of this path is "swap back to sats" (`swapBackToSats`). cashu-wallet `KeysetDirectory.refresh()` has no caller in the library; the
  consuming app decides when to refresh.
- **Mitigation.** Rotate at low traffic. Just before, check for in-flight work and let it drain
  (no pending receive intents or swaps on the operator's own wallets; no quotes in progress on
  the mint). After the rotation, a `12002` for an output keyset that was active a minute ago is the
  expected refusal, not an incident. Until #248 ships, a receive caught across the rotation stays
  pending with its value kept until the user restarts the app; record any such case in the ticket.
- **One mint at a time:** rotate one mint, run the per-mint check below, then stop before the next.
  Staging is expected to have a single mint; say so in the ticket if it does.

**Check per mint:** the control reaches `KEY_ROTATION_COMPLETED`; its outcome names the new
keyset and the one it replaced; then **wait more than 60 s** (the mint caches keysets for
`mint.keyset-cache.ttl-seconds`, default 60, so it may still advertise the old keyset as active
just after the rotation) before checking that `/v1/keysets` shows the new id `active: true`
(66 hex chars, `01` prefix) and the old id `active: false`; a test mint of a few sats and a swap
of those proofs succeed on the new keyset. For staging, run the local gate first (below),
including a rehearsal of "register the fixture-seeded mint in mint-admin" with a copy of
`imani-deploy/config/test/preload-test-data.json`. Registration leaves the existing active keyset
in place either way; a wrong unit or ladder there decides a different **rotated** keyset.

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
- Deploy: roll back to the literal 0.43.1 digest (and put it back in `.env.staging` and
  `build-refs.env` if the pin was made durable), see step 1.
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
