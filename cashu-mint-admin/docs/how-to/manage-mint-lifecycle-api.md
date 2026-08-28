# Manage the mint lifecycle via the Admin REST API

This guide shows how to call the administrative lifecycle endpoints exposed by the `mint-admin-rest` module to provision, update, pause, resume, and retire mint instances.

It is the scripting route, for CI and bootstrap scripts. An operator working by
hand should use [Create a mint and put it into service](create-a-mint.md), which
covers the same sequence through the admin interface.

## Prerequisites

- A running `mint-admin-rest` service (start
  [`CashuMintAdminRestApplication`](../../mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/CashuMintAdminRestApplication.java))
  with the `/admin` routes enabled.
- An Operator profile carrying your npub and a role granting `MINT_ADMIN` access.
- A tool capable of issuing HTTPS requests (the examples below use `curl`).

## 1. Export common variables

Define the base URL once, then sign in with a NAP handshake and keep the session
cookie it sets:

```bash
export MINT_ADMIN_API="https://admin.mint.example.com"
export ADMIN_SESSION="<cashu_admin_session value from POST /api/v1/auth/complete>"
```

Point `MINT_ADMIN_API` at the host running the admin service—`mint-admin-rest`
exposes `/admin` routes separately from the public mint API.

Each request must carry a `cashu_admin_session` cookie from a NAP handshake, and the
Operator behind it must hold the `mint:lifecycle` permission enforced by
[`LifecycleAdminController`](../../mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/controller/LifecycleAdminController.java).

## 2. Provision a mint

Send a `POST /admin/lifecycle/mints` request with the operator details, display metadata, and initial configuration defined by [`CreateMintRequest`](../../mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/CreateMintRequest.java):

```bash
curl -X POST "$MINT_ADMIN_API/admin/lifecycle/mints" \
  -H "Content-Type: application/json" \
  -b "cashu_admin_session=$ADMIN_SESSION" \
  -d '{
        "mintId": "mint-001",
        "metadata": {
          "displayName": "Primary mint",
          "description": "Production instance",
          "tags": ["prod", "eu-west"]
        },
        "configuration": {
          "versionTag": "2024-Q2",
          "cashu.unit": "sat",
          "cashu.denominations": "1,2,4,8,16,32,64,128,256,512,1024"
        }
      }'
```

The response is a [`LifecycleActionResponse`](../../mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/LifecycleActionResponse.java) describing the resulting state. Pipe the output to `jq` to verify the `currentState` and `message` fields.

`cashu.unit` and `cashu.denominations` are the only two configuration keys that
change what is provisioned; everything else is stored and versioned but has no
effect on the keyset. Omit them and the mint is provisioned with `sat` and
denominations `1..128`.

Creating the mint returns as soon as the record exists. A background saga then
writes one key per denomination into HashiCorp under
`cashu/keys/<mintId>/<keySetId>/<amount>`, moving the mint from `PROVISIONING` to
`PROVISIONED`, so poll until it settles:

```bash
curl -s "$MINT_ADMIN_API/admin/lifecycle/mints/mint-001" \
  -b "cashu_admin_session=$ADMIN_SESSION" | jq -r .lifecycleState
```

`PROVISION_FAILED` means the saga could not write to the vault; the admin service
log names the cause. Once provisioned, back the keys up before the mint takes
traffic: see [Back up a keyset's private keys](back-up-keyset-private-keys.md).

## 3. Update lifecycle metadata

To record a new configuration revision or change descriptive metadata, call `PUT /admin/lifecycle/mints/{mintId}` with the schema from [`UpdateMintRequest`](../../mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/UpdateMintRequest.java):

```bash
curl -X PUT "$MINT_ADMIN_API/admin/lifecycle/mints/mint-001" \
  -H "Content-Type: application/json" \
  -b "cashu_admin_session=$ADMIN_SESSION" \
  -d '{
        "metadata": {
          "displayName": "Primary mint",
          "description": "Production instance (Q2 rollout)",
          "tags": ["prod", "eu-west"]
        },
        "configuration": {"versionTag": "2024-Q3"},
        "revisionId": "rev-2024-q3"
      }'
```

A `changed` flag of `true` indicates that the new revision was accepted; `false` means the request was idempotent.

## 4. Pause and resume operations

Lifecycle transitions such as maintenance pauses and resumptions use [`LifecycleChangeRequest`](../../mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/LifecycleChangeRequest.java). Supply a reason for auditing, and reuse the optional `correlationId` to link related actions:

```bash
curl -X POST "$MINT_ADMIN_API/admin/lifecycle/mints/mint-001/pause" \
  -H "Content-Type: application/json" \
  -b "cashu_admin_session=$ADMIN_SESSION" \
  -d '{
        "reason": "Apply security patches",
        "correlationId": "maintenance-window-42"
      }'

curl -X POST "$MINT_ADMIN_API/admin/lifecycle/mints/mint-001/resume" \
  -H "Content-Type: application/json" \
  -b "cashu_admin_session=$ADMIN_SESSION" \
  -d '{
        "reason": "Post-maintenance validation passed",
        "correlationId": "maintenance-window-42"
      }'
```

The responses confirm the transition from `previousState` to `currentState` so runbooks can assert the mint status.

`resume` does double duty: against a `PROVISIONED` mint it is the activation
call, `PROVISIONED → ACTIVE`, which is why the admin interface labels that button
**Activate**. Activation requires sign-off from both Operations and Security.

It fails with `409 unit_conflict` when another mint is already `ACTIVE` for the
same unit, because a unit may have only one active mint; pause or retire the
incumbent first. A transition that is not legal for the mint's current state
fails with `409 invalid_transition`.

## 5. Retire a mint

Retiring a mint follows the same pattern but targets `/admin/lifecycle/mints/{mintId}/retire` and typically omits the correlation identifier:

```bash
curl -X POST "$MINT_ADMIN_API/admin/lifecycle/mints/mint-001/retire" \
  -H "Content-Type: application/json" \
  -b "cashu_admin_session=$ADMIN_SESSION" \
  -d '{
        "reason": "Replaced by mint-002"
      }'
```

A successful response reports `currentState` as `DECOMMISSIONED`. If the mint was already retired, the API returns the same summary with `changed` set to `false`, signalling that no additional work is required.

## 6. Point a mint service at the same vault

No API call does this, and until it is done the mint is `ACTIVE` in the admin and
no mint service is serving it. There is no mint-id setting on the mint service:
it discovers which mints it serves by reading the shared vault, so the vault is
the link. Configure it once per environment:

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

`MINT_PRELOAD_ENABLED` decides whether the link is real: left at its default
(`true`) the mint serves a fixed keyset from a JSON file, so a mint the admin
provisioned never appears and a rotation never reaches `/v1/keysets`. The engine
mount must match on both sides, or the mint looks for the keys the admin wrote
under a path that does not exist.

Confirm with `curl -s "$MINT_URL/v1/keysets" | jq`: the provisioned keyset should
be listed and `active`. An empty list means the mint service is reading a
different vault, or is still on preload JSON.

A vault-backed mint service serves **every** mint in the vault it reads. To keep
environments apart, give each its own vault and admin database rather than
pointing several mint services at a shared one.
