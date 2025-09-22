# REST API Reference
This document provides details about the Cashu Mint REST API endpoints. All paths are rooted at `/v1`.

## Endpoints

## Endpoint details


### `GET /keys/{mint_id}/generate`
Generate keyset IDs for a mint.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
**Sample request**
```http
GET /keys/123/generate HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "keyset_ids": ["abc123"]
}
```


### `GET /keys/keyset/{keyset_id}`
Retrieve keys for a specific keyset.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `keyset_id` | path | string | Identifier of the keyset. |
**Sample request**
```http
GET /keys/keyset/abc123 HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "keys": ["-----BEGIN PUBLIC KEY-----\n..."]
}
```


### `GET /keysets`
List all keysets (active and inactive) with an `active` flag.
_No parameters._
**Sample request**
```http
GET /keysets HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "keysets": ["abc123", "def456"]
}
```



### `POST /swap/{mint_id}`
Swap tokens within a mint.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
| `body` | body | object | Swap request payload. |
**Sample request**
```http
POST /swap/123 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "inputs": [],
  "outputs": []
}
```
**Sample response**
```json
{
  "token": "..."
}
```


### `POST /mint/quote/{method}`
Request a mint quote for a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method (e.g., `bolt11`). |
| `body` | body | object | Quote request payload. |
**Sample request**
```http
POST /mint/quote/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "amount": 100
}
```
**Sample response**
```json
{
  "quote_id": "q123",
  "request": "lnbc1..."
}
```


### `GET /mint/quote/{method}/{quote_id}`
Check status of a mint quote.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method. |
| `quote_id` | path | string | Identifier returned from quote request. |
**Sample request**
```http
GET /mint/quote/bolt11/q123 HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "paid": true
}
```


### `POST /mint/{mintId}/{method}`
Mint tokens using a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mintId` | path | string | Unique mint identifier. |
| `method` | path | string | Payment method. |
| `body` | body | object | Mint request payload. |
**Sample request**
```http
POST /mint/123/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "quote_id": "q123"
}
```
**Sample response**
```json
{
  "token": "..."
}
```


### `POST /melt/quote/{method}`
Request a melt quote for a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method (e.g., `bolt11`). |
| `body` | body | object | Quote request payload. |
**Sample request**
```http
POST /melt/quote/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "amount": 100
}
```
**Sample response**
```json
{
  "quote_id": "m123",
  "request": "lnbc1..."
}
```


### `GET /melt/quote/{method}/{quote_id}`
Check status of a melt quote.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method. |
| `quote_id` | path | string | Identifier returned from melt quote request. |
**Sample request**
```http
GET /melt/quote/bolt11/m123 HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "paid": true
}
```


### `POST /melt/{mint_id}/{method}`
Melt tokens using a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
| `method` | path | string | Payment method. |
| `body` | body | object | Melt request payload. |
**Sample request**
```http
POST /melt/123/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "quote_id": "m123",
  "token": "..."
}
```
**Sample response**
```json
{
  "paid": true
}
```


### `GET /info`
Retrieve mint information.
_No parameters._
**Sample request**
```http
GET /info HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "name": "Cashu Mint"
}
```


### `POST /checkstate/{mint_id}`
Check state of tokens against a mint.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
| `body` | body | object | State check payload. |
**Sample request**
```http
POST /checkstate/123 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "tokens": []
}
```
**Sample response**
```json
{
  "spent": []
}
```


### `POST /restore`
Restore blind signatures for previously signed messages (NUT-09).
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `body` | body | object | Restore request payload containing blinded messages. |
**Sample request**
```http
POST /restore HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "blinded_messages": [
    { "B_": "..." }
  ]
}
```
**Sample response**
```json
{
  "outputs": [ { "B_": "..." } ],
  "signatures": [ "..." ]
}
```

## Administrative API

Administrative endpoints provided by the `cashu-mint-admin-rest` service live under the `/admin` path and require both the
`X-Admin-Token` header and an `X-Admin-Roles` header indicating the caller's role. The
token value defaults to `local-dev-token` for local development and can be overridden
with the `ADMIN_API_TOKEN` environment variable. Supported roles include `MINT_ADMIN`,
`USER_ADMIN`, and `ALERTS_ADMIN` depending on the target workflow. Responses mirror the
CLI output and are documented via OpenAPI at `/v3/api-docs`.

### `POST /admin/lifecycle/mints`
Provision a new mint instance (role: `MINT_ADMIN`). Returns a
[`LifecycleActionResponse`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/lifecycle/LifecycleActionResponse.java)
summarising the change.
**Sample request**
```http
POST /admin/lifecycle/mints HTTP/1.1
Host: example.com
Content-Type: application/json
X-Admin-Token: local-dev-token
X-Admin-Roles: MINT_ADMIN
{
  "mintId": "mint-001",
  "requestedBy": {"id": "ops", "displayName": "Ops"},
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

### `POST /admin/users`
Create a new operator account (role: `USER_ADMIN`). Returns a
[`UserResponse`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/users/UserResponse.java)
with the active status and assigned roles.

### `PUT /admin/users/{userId}`
Update operator account details.

### `POST /admin/users/{userId}/roles`
Assign roles to an operator account.

### `POST /admin/users/{userId}/reset-credentials`
Trigger a credential reset workflow for an operator (role: `USER_ADMIN`). Returns a
[`CredentialResetResponse`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/users/CredentialResetResponse.java)
containing the reset token.

### `POST /admin/users/{userId}/deactivate`
Deactivate an operator account.

### `POST /admin/alerts`
Declare an operational alert (role: `ALERTS_ADMIN`). Returns an
[`AlertActionResponse`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/dto/alerts/AlertActionResponse.java)
describing the alert's acknowledgement, silence, and escalation state.

### `POST /admin/alerts/{alertId}/acknowledge`
Acknowledge an alert.

### `POST /admin/alerts/{alertId}/silence`
Silence alert notifications for a period.

### `POST /admin/alerts/{alertId}/unsilence`
Unsilence an alert, resuming notifications.

### `POST /admin/alerts/{alertId}/escalate`
Escalate an alert to an external policy.
