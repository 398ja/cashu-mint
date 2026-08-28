# REST API reference

The admin REST API runs on port 7778 by default. Every `/admin` endpoint requires a
`cashu_admin_session` cookie from a NAP handshake, and the permission its controller
names. OpenAPI is served at `/v3/api-docs`.

## Authentication — `/api/v1/auth`

Provided by the NAP library rather than by this module.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/auth/init` | Start a handshake for an npub; returns a challenge |
| `POST` | `/api/v1/auth/complete` | Answer the challenge with a signed proof; sets the session cookie |
| `GET` | `/api/v1/auth/session` | Returns the caller's roles and permissions |

The Super Administrator's npub comes from `ADMIN_SUPER_ADMIN_NPUB`; every other
Operator needs a stored profile carrying their npub. See
[Configure NAP admin authentication](../how-to/configure-nap-admin-authentication.md).

## Roles and permissions

Each controller names one permission. Roles are bundles of permissions:

| Role | Permissions |
|------|-------------|
| `SUPER_ADMIN` | all of them; the account that recovers a deployment whose other Operators are locked out |
| `MINT_ADMIN` | `mint:lifecycle`, `operations:execute`, `audit:read`, `dashboard:read` |
| `OPS_ADMIN` | `operations:execute`, `audit:read`, `dashboard:read` |
| `USER_ADMIN` | `users:manage`, `audit:read`, `dashboard:read` |

Every role reads the audit trail: it is read-only, it is how an Operator checks what
was done to a deployment they are on call for, and the dashboard's recent-activity
panel is built from it.

## Dashboard — `/admin/dashboard`

Permission: `dashboard:read`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/dashboard/summary` | Aggregated counts of mints by state and active controls |

## Lifecycle — `/admin/lifecycle`

Permission: `mint:lifecycle`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/lifecycle/mints` | List mints (paginated, filterable by state) |
| `GET` | `/admin/lifecycle/mints/{mintId}` | Get mint detail |
| `GET` | `/admin/lifecycle/mints/{mintId}/keysets` | List the mint's keysets (paginated) |
| `GET` | `/admin/lifecycle/mints/{mintId}/keysets/{keySetId}/denominations` | List a keyset's denominations and vault paths |
| `POST` | `/admin/lifecycle/mints` | Provision a new mint |
| `PUT` | `/admin/lifecycle/mints/{mintId}` | Update metadata or configuration |
| `POST` | `/admin/lifecycle/mints/{mintId}/pause` | Pause mint operations (`ACTIVE → SUSPENDED`) |
| `POST` | `/admin/lifecycle/mints/{mintId}/resume` | Activate a provisioned mint, or resume a paused one |
| `POST` | `/admin/lifecycle/mints/{mintId}/retire` | Retire and decommission a mint |

`resume` does double duty: against a `PROVISIONED` mint it is the activation call
(`PROVISIONED → ACTIVE`), which is why the web interface labels that button
**Activate**. It returns `409 unit_conflict` when another mint is already active for
the same unit, and `409 invalid_transition` for a transition illegal in the mint's
current state.

## Operations — `/admin/operations`

Permission: `operations:execute`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/operations/mints/{mintId}/controls` | List operational controls (paginated) |
| `POST` | `/admin/operations/mints/{mintId}/maintenance/schedule` | Schedule a maintenance window |
| `POST` | `/admin/operations/mints/{mintId}/maintenance/start` | Start maintenance |
| `POST` | `/admin/operations/mints/{mintId}/maintenance/complete` | Complete maintenance |
| `POST` | `/admin/operations/mints/{mintId}/keys/rotate` | Rotate the mint's signing keyset |
| `POST` | `/admin/operations/mints/{mintId}/force-close` | Emergency decommission |

Rotation is asynchronous: the call returns a `controlId`, and the controls listing
carries the `status` and an `outcome` naming which keyset replaced which once the
saga completes.

## Users — `/admin/users`

Permission: `users:manage`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/users` | List operators (paginated, filterable by role/active) |
| `GET` | `/admin/users/{userId}` | Get operator detail |
| `POST` | `/admin/users` | Create an operator |
| `PUT` | `/admin/users/{userId}` | Update an operator |
| `POST` | `/admin/users/{userId}/roles` | Assign roles |
| `POST` | `/admin/users/{userId}/deactivate` | Deactivate an operator |
| `POST` | `/admin/users/{userId}/reinstate` | Reinstate a deactivated operator |

`SUPER_ADMIN` cannot be granted through this API: it is configuration, so an
Operator who may edit roles cannot grant themselves the role that outranks them.

## Audit — `/admin/audit`

Permission: `audit:read`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/audit/events` | List audit events (paginated, filterable by mint/actor/action) |

## Pagination

List endpoints accept `page` (zero-indexed, default 0) and `size` (default 20).
Responses carry `items`, `page`, `size`, `totalItems` and `totalPages`.

## Errors

| Status | Meaning |
|--------|---------|
| 400 | Validation error (see the response body) |
| 401 | No session, or an expired one |
| 403 | Authenticated, but the role lacks the permission the endpoint requires |
| 404 | Resource not found |
| 409 | Conflict: `invalid_transition`, `unit_conflict`, or a duplicate resource |
| 500 | Internal server error |

## See also

- [Create a mint and put it into service](../how-to/create-a-mint.md)
- [Manage the mint lifecycle via the Admin REST API](../how-to/manage-mint-lifecycle-api.md)
- [Configuration](configuration.md) — application properties
