# Manage the mint lifecycle via the Admin REST API

This guide shows how to call the administrative lifecycle endpoints exposed by the `mint-admin-rest` module to provision, update, pause, resume, and retire mint instances.

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
        "configuration": {"versionTag": "2024-Q2"}
      }'
```

The response is a [`LifecycleActionResponse`](../../mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/LifecycleActionResponse.java) describing the resulting state. Pipe the output to `jq` to verify the `currentState` and `message` fields.

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

