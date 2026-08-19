# REST API reference

The admin REST API runs on port 7778 by default. All endpoints except `/admin/auth/me` require the `X-Admin-Token` header.

## Authentication — `/admin/auth`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/auth/me` | Returns caller identity and roles (public) |

## Dashboard — `/admin/dashboard`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/dashboard/summary` | Aggregated counts of mints, alerts, and active controls |

## Lifecycle — `/admin/lifecycle`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/lifecycle/mints` | List mints (paginated, filterable by state) |
| `GET` | `/admin/lifecycle/mints/{mintId}` | Get mint detail |
| `POST` | `/admin/lifecycle/mints` | Provision a new mint |
| `PUT` | `/admin/lifecycle/mints/{mintId}` | Update a mint |
| `POST` | `/admin/lifecycle/mints/{mintId}/pause` | Pause mint operations |
| `POST` | `/admin/lifecycle/mints/{mintId}/resume` | Resume a paused mint |
| `POST` | `/admin/lifecycle/mints/{mintId}/retire` | Retire and decommission a mint |

## Configuration — `/admin/configuration`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/configuration/mints/{mintId}/revisions` | List configuration revisions (paginated) |
| `POST` | `/admin/configuration/mints/{mintId}/preview` | Preview configuration changes without persisting |
| `POST` | `/admin/configuration/mints/{mintId}/apply` | Apply configuration changes |
| `POST` | `/admin/configuration/mints/{mintId}/rollback` | Rollback to a previous revision |

## Users — `/admin/users`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/users` | List operators (paginated, filterable by role/active) |
| `GET` | `/admin/users/{userId}` | Get operator detail |
| `POST` | `/admin/users` | Create an operator |
| `PUT` | `/admin/users/{userId}` | Update an operator |
| `POST` | `/admin/users/{userId}/roles` | Assign roles |
| `POST` | `/admin/users/{userId}/reset-credentials` | Reset credentials |
| `POST` | `/admin/users/{userId}/deactivate` | Deactivate an operator |

## Alerts — `/admin/alerts`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/alerts` | List alerts (paginated, filterable by severity/mint) |
| `GET` | `/admin/alerts/{alertId}` | Get alert detail |
| `POST` | `/admin/alerts` | Create an alert |
| `POST` | `/admin/alerts/{alertId}/acknowledge` | Acknowledge an alert |
| `POST` | `/admin/alerts/{alertId}/silence` | Silence notifications |
| `POST` | `/admin/alerts/{alertId}/unsilence` | Unsilence an alert |
| `POST` | `/admin/alerts/{alertId}/escalate` | Escalate an alert |

## Operations — `/admin/operations`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/operations/mints/{mintId}/controls` | List operational controls (paginated) |
| `POST` | `/admin/operations/mints/{mintId}/maintenance/schedule` | Schedule maintenance window |
| `POST` | `/admin/operations/mints/{mintId}/maintenance/start` | Start maintenance |
| `POST` | `/admin/operations/mints/{mintId}/maintenance/complete` | Complete maintenance |
| `POST` | `/admin/operations/mints/{mintId}/keys/rotate` | Rotate keys |
| `POST` | `/admin/operations/mints/{mintId}/force-close` | Emergency decommission |

## Health — `/admin/health`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/health/mints/{mintId}` | Get health snapshot |
| `POST` | `/admin/health/mints/{mintId}/acknowledge` | Acknowledge health alert |

## Audit — `/admin/audit`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/audit/events` | List audit events (paginated, filterable by mint/actor/action) |

## Pagination

List endpoints accept `page` (zero-indexed, default 0) and `size` (default 20) query parameters. Responses use `PagedResponse` with `content`, `page`, `size`, and `totalElements` fields.

## Errors

Standard error responses use HTTP status codes:

| Status | Meaning |
|--------|---------|
| 400 | Validation error (see response body for details) |
| 401 | Missing or invalid `X-Admin-Token` |
| 404 | Resource not found |
| 409 | Conflict (duplicate resource or revision conflict) |
| 500 | Internal server error |

## See also

- [Configuration](configuration.md) — application properties
