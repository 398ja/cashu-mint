# Create a mint instance and link it to a mint service

This guide takes you from an empty environment to a running mint whose keysets
the admin controls: create the mint in the admin, let the saga provision its key
material, then point a mint service at the same vault so it serves what the admin
provisioned.

## Before you start

- The admin API reachable, and an Operator session (see
  [Configure NAP admin authentication](configure-nap-admin-authentication.md)).
- A `cashu-vault-jpa` service and a HashiCorp Vault both reachable from the admin
  and from the mint.
- A mint id. You choose it: the admin does not generate one, and it is the only
  thing that ties the two services together.

## 1. Create the mint

```bash
curl -X POST "$ADMIN_URL/admin/lifecycle/mints" \
  -H 'Content-Type: application/json' \
  -b "$SESSION_COOKIE" \
  -d '{
        "mintId": "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae",
        "metadata": {
          "displayName": "EU Mint",
          "description": "production mint, EU region",
          "tags": ["prod"]
        },
        "configuration": {
          "cashu.unit": "sat",
          "cashu.denominations": "1,2,4,8,16,32,64,128,256,512,1024"
        }
      }'
```

`cashu.unit` and `cashu.denominations` are the two keys the provisioning and
rotation sagas read. Anything else in `configuration` is stored and versioned but
does not affect what is provisioned. Omit them and the mint is provisioned with
`sat` and denominations `1..128`.

## 2. Wait for provisioning

Creating the mint writes a `CREATED` outbox message; a background saga provisions
the vault and moves the mint from `PROVISIONING` to `PROVISIONED`.

```bash
curl -s "$ADMIN_URL/admin/lifecycle/mints/$MINT_ID" -b "$SESSION_COOKIE" \
  | jq -r .lifecycleState
```

Wait for `PROVISIONED`. `PROVISION_FAILED` means the saga could not write to the
vault; the mint's own log names the cause.

The saga writes one key per denomination into HashiCorp under
`cashu/keys/<mintId>/<keySetId>/<amount>`, and a `t_key` row that points at each
path. Back them up before the mint takes traffic: see
[Back up a keyset's private keys](back-up-keyset-private-keys.md).

## 3. Point the mint service at the same vault

There is no mint-id setting on the mint service. It discovers which mints it
serves by reading the shared vault, so the vault *is* the link. Configure it with:

```yaml
MINT_PRELOAD_ENABLED: "false"        # serve keysets from the vault, not preload JSON
CASHU_VAULT_BASE_URL: http://cashu-vault-jpa:3333
VAULT_BASE_URL: http://cashu-vault-jpa:3333
VAULT_BACKEND: HASHICORP
VAULT_HASHI_ENABLED: "true"          # without this it silently falls back to the DB vault
VAULT_HASHI_URI: http://hashicorp-vault:8200
VAULT_HASHI_AUTH_METHOD: TOKEN
VAULT_HASHI_AUTH_TOKEN: <token>
VAULT_HASHI_ENGINE_MOUNT: cashu      # must match the admin's mount
```

`MINT_PRELOAD_ENABLED` is the one that decides whether the link is real.
Left at its default (`true`), `PreloadMintLoadService` is `@Primary` and serves a
fixed keyset from JSON: the admin and the mint share a vault and still disagree,
and a rotation is invisible at `/v1/keysets`.

The engine mount must match on both sides, or the mint will look for the keys the
admin wrote under a path that does not exist.

## 4. Confirm the link

```bash
curl -s "$MINT_URL/v1/keysets" | jq
```

The keyset the admin provisioned should be listed and `active`. If the list is
empty, the mint is reaching a different vault, or is still on preload JSON.

Now rotate, and watch it reach the mint:

```bash
curl -X POST "$ADMIN_URL/admin/operations/mints/$MINT_ID/keys/rotate" \
  -H 'Content-Type: application/json' -b "$SESSION_COOKIE" \
  -d '{"reason": "scheduled rotation"}'
```

`/v1/keysets` should then advertise a new active keyset, with the previous one
still listed and `active: false`. Archived keysets are never deleted: tokens they
signed stay redeemable, which is why NUT-02 requires them to go on verifying.

`GET /admin/operations/mints/{mintId}/controls` records the outcome, naming which
keyset replaced which.

## Activating the mint

`PROVISIONED` means the key material exists. Moving to `ACTIVE` requires sign-off
from both Operations and Security:

```bash
curl -X POST "$ADMIN_URL/admin/lifecycle/mints/$MINT_ID/resume" \
  -H 'Content-Type: application/json' -b "$SESSION_COOKIE" \
  -d '{"reason": "go live"}'
```

## One vault, every mint

A vault-backed mint serves **every** mint in the vault it reads, not only one. To
isolate environments, give each its own vault rather than pointing several mint
services at a shared one.
