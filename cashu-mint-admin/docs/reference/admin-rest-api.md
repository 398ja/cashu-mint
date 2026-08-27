# Admin REST API Reference

This document describes the administrative HTTP endpoints exposed by the admin module (`mint-admin-rest`). Endpoints are not versioned and are rooted at `/admin` (no `/v1` prefix).

## Authentication and Roles
- Required headers: `X-Admin-Token` and `X-Admin-Roles`.
- Default dev token: `local-dev-token` (override with the `ADMIN_API_TOKEN` environment variable).
- Example roles: `MINT_ADMIN`, `USER_ADMIN`, `ALERTS_ADMIN`.
- OpenAPI docs are served at `/v3/api-docs` by the admin service.

## Lifecycle

### `POST /admin/lifecycle/mints`
Provision a new mint instance (role: `MINT_ADMIN`). Returns a lifecycle action response summarizing the change.
**Sample request**
```http
POST /admin/lifecycle/mints HTTP/1.1
Host: example.com
Content-Type: application/json
X-Admin-Token: local-dev-token
X-Admin-Roles: MINT_ADMIN
{
  "mintId": "mint-001",
  "metadata": {"displayName": "Primary", "description": "Prod mint"},
  "configuration": {"versionTag": "2024-Q1"}
}
```
**Sample response**
```json
{
  "operation": "CREATE",
  "mintId": "mint-001",
  "previousState": null,
  "currentState": "PROVISIONED",
  "versionTag": "2024-Q1",
  "changed": true,
  "message": "Mint created"
}
```

### `PUT /admin/lifecycle/mints/{mintId}`
Update metadata or configuration bindings for an existing mint.

### `POST /admin/lifecycle/mints/{mintId}/pause`
Pause mint operations for maintenance.

### `POST /admin/lifecycle/mints/{mintId}/resume`
Resume a paused mint.

### `POST /admin/lifecycle/mints/{mintId}/retire`
Retire a mint and revoke access.

## Configuration

### `POST /admin/configuration/mints/{mintId}/preview`
Preview configuration changes without applying them.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mintId` | path | string | Target mint identifier. |
| `body` | body | object | Configuration preview payload containing the proposed changes. |

### `POST /admin/configuration/mints/{mintId}/apply`
Apply a configuration change set to a mint (role: `MINT_ADMIN`).
**Sample response**
```json
{
  "mintId": "mint-001",
  "revisionId": "rev-1",
  "parameters": {
    "currency": "sat",
    "limits": "{\"max\":1}"
  },
  "message": "Configuration applied: increase limit"
}
```

### `POST /admin/configuration/mints/{mintId}/rollback`
Rollback a mint to a previous configuration revision.

## Users

### `POST /admin/users`
Create a new operator account (role: `USER_ADMIN`). Returns user details with active status and assigned roles.

### `PUT /admin/users/{userId}`
Update operator account details.

### `POST /admin/users/{userId}/roles`
Assign roles to an operator account.

### `POST /admin/users/{userId}/reset-credentials`
Trigger a credential reset workflow for an operator (role: `USER_ADMIN`). Returns a reset token.

### `POST /admin/users/{userId}/deactivate`
Deactivate an operator account.

## Alerts

### `POST /admin/alerts`
Declare an operational alert (role: `ALERTS_ADMIN`). Returns an alert action response describing acknowledgement, silence, and escalation state.

### `POST /admin/alerts/{alertId}/acknowledge`
Acknowledge an alert.

### `POST /admin/alerts/{alertId}/silence`
Silence alert notifications for a period.

### `POST /admin/alerts/{alertId}/unsilence`
Unsilence an alert, resuming notifications.

### `POST /admin/alerts/{alertId}/escalate`
Escalate an alert to an external policy.

