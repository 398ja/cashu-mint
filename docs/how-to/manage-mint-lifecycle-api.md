# Manage the mint lifecycle via the REST API

This guide shows how to call the administrative lifecycle endpoints exposed by the `cashu-mint-admin-rest` module to provision, update, pause, resume, and retire mint instances.

## Prerequisites

- A running Cashu mint admin REST service with the `/admin` routes enabled.
- An administrator token and role granting `MINT_ADMIN` access.
- A tool capable of issuing HTTPS requests (the examples below use `curl`).

## 1. Export common variables

Define the base URL, token, and role headers once to simplify subsequent commands:

```bash
export MINT_API="https://mint.example.com"
export ADMIN_TOKEN="local-dev-token"
export ADMIN_ROLES="MINT_ADMIN"
```

Each request must include `X-Admin-Token` and `X-Admin-Roles` headers as enforced by [`LifecycleAdminController`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/controller/LifecycleAdminController.java).

## 2. Provision a mint

Send a `POST /admin/lifecycle/mints` request with the operator details, display metadata, and initial configuration defined by [`CreateMintRequest`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/CreateMintRequest.java):

```bash
curl -X POST "$MINT_API/admin/lifecycle/mints" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "X-Admin-Roles: $ADMIN_ROLES" \
  -d '{
        "mintId": "mint-001",
        "requestedBy": {"id": "ops", "displayName": "Ops Bot"},
        "metadata": {
          "displayName": "Primary mint",
          "description": "Production instance",
          "tags": ["prod", "eu-west"]
        },
        "configuration": {"versionTag": "2024-Q2"}
      }'
```

The response is a [`LifecycleActionResponse`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/LifecycleActionResponse.java) describing the resulting state. Pipe the output to `jq` to verify the `currentState` and `message` fields.

## 3. Update lifecycle metadata

To record a new configuration revision or change descriptive metadata, call `PUT /admin/lifecycle/mints/{mintId}` with the schema from [`UpdateMintRequest`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/UpdateMintRequest.java):

```bash
curl -X PUT "$MINT_API/admin/lifecycle/mints/mint-001" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "X-Admin-Roles: $ADMIN_ROLES" \
  -d '{
        "requestedBy": {"id": "ops", "displayName": "Ops Bot"},
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

Lifecycle transitions such as maintenance pauses and resumptions use [`LifecycleChangeRequest`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/LifecycleChangeRequest.java). Supply a reason for auditing, and reuse the optional `correlationId` to link related actions:

```bash
curl -X POST "$MINT_API/admin/lifecycle/mints/mint-001/pause" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "X-Admin-Roles: $ADMIN_ROLES" \
  -d '{
        "requestedBy": {"id": "ops", "displayName": "Ops Bot"},
        "reason": "Apply security patches",
        "correlationId": "maintenance-window-42"
      }'

curl -X POST "$MINT_API/admin/lifecycle/mints/mint-001/resume" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "X-Admin-Roles: $ADMIN_ROLES" \
  -d '{
        "requestedBy": {"id": "ops", "displayName": "Ops Bot"},
        "reason": "Post-maintenance validation passed",
        "correlationId": "maintenance-window-42"
      }'
```

The responses confirm the transition from `previousState` to `currentState` so runbooks can assert the mint status.

## 5. Retire a mint

Retiring a mint follows the same pattern but targets `/retire` and typically omits the correlation identifier:

```bash
curl -X POST "$MINT_API/admin/lifecycle/mints/mint-001/retire" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "X-Admin-Roles: $ADMIN_ROLES" \
  -d '{
        "requestedBy": {"id": "ops", "displayName": "Ops Bot"},
        "reason": "Replaced by mint-002"
      }'
```

A successful response reports `currentState` as `DECOMMISSIONED`. If the mint was already retired, the API returns the same summary with `changed` set to `false`, signalling that no additional work is required.
