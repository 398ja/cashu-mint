# Cashu Mint Admin User Guide

> **Superseded in parts.** This guide predates the removal of the CLI, alerts,
> health and configuration governance, and the move to per-operator credentials.
> Its `mint-admin-cli` commands, `X-Admin-Roles` headers and alert workflows no
> longer exist. See `../explanations/admin-triage.md` and `docs/adr/0005`.
> Rewriting it is tracked separately.


This guide walks you through everything you need to operate a Cashu mint using the admin module. It covers the web interface, the REST API, and the CLI — starting from zero and building up to day-to-day operational tasks.

## What Is the Admin Module?

The admin module is the control plane for your Cashu mint. It lets you:

- **Create and provision** new mints (a mint is a virtual ecash issuer for a specific currency unit like `sat` or `usd`).
- **Control the lifecycle** of each mint — activate it, pause it for maintenance, and retire it when done.
- **Manage configuration** — change mint parameters, review revision history, and roll back if something goes wrong.
- **Monitor health** — check whether your mint is healthy, degraded, or down.
- **Handle alerts** — acknowledge, silence, or escalate operational alerts.
- **Manage operators** — create operator accounts, assign roles, and reset credentials.
- **Schedule maintenance** — plan maintenance windows, rotate keys, or force-close a mint in emergencies.
- **Audit everything** — every action is logged with who did it, when, and why.

You can do all of this through three interfaces:

| Interface | Best for | Port |
|-----------|----------|------|
| **Web UI** | Day-to-day operations, visual overview | 3000 |
| **REST API** | Automation, scripting, CI/CD integration | 7778 |
| **CLI** | Quick terminal commands, shell scripts | (connects to REST) |

---

## Getting Started

### Starting the Development Stack

The fastest way to get everything running is with Docker Compose:

```bash
cd /path/to/cashu-mint
docker compose -f docker-compose.dev.yml up -d
```

This starts all required services:

| Service | Description | URL |
|---------|-------------|-----|
| `admin-db` | PostgreSQL database for admin data | `localhost:55435` |
| `cashu-mint-admin-rest` | Admin REST API | `http://localhost:7778` |
| `cashu-mint-admin-web` | Web interface | `http://localhost:3000` |
| `cashu-vault-jpa` | Vault service (key/proof storage) | `http://localhost:3333` |
| `cashu-vault-db` | Vault PostgreSQL database | `localhost:55433` |

Wait for all services to become healthy:

```bash
docker compose -f docker-compose.dev.yml ps
```

All services should show `healthy` status before proceeding.

### Your First Login (Web UI)

1. Open `http://localhost:3000` in your browser.
2. You will see a login page asking for an **Admin Token**.
3. Enter the development token: `local-dev-token`
4. Click **Login**.

You are now on the **Dashboard**, which shows a summary of your mints, alerts, and operational controls.

### Authentication

All admin operations require a token. In development, the default token is `local-dev-token`. In production, set a strong token via the `ADMIN_API_TOKEN` environment variable.

**For the REST API**, pass the token in the `X-Admin-Token` header:

```bash
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: MINT_ADMIN" \
     http://localhost:7778/admin/lifecycle/mints
```

**For the CLI**, pass it as a flag:

```bash
java -jar mint-admin-cli-*-runner.jar \
  --api-url http://localhost:7778 \
  --api-key local-dev-token \
  mint
```

### Roles

The admin module uses role-based access control. You must include your roles in the `X-Admin-Roles` header (REST API) or they are selected at login (web UI).

| Role | What you can do |
|------|----------------|
| `MINT_ADMIN` | Create, activate, pause, retire mints. Manage configuration. |
| `USER_ADMIN` | Create and manage operator accounts. Assign roles. Reset credentials. |
| `ALERTS_ADMIN` | View, acknowledge, silence, and escalate alerts. |
| `OPS_ADMIN` | Schedule maintenance, rotate keys, force-close mints. |

A single operator can have multiple roles. In development, you typically use all four.

---

## Creating Your First Mint

A **mint** is a virtual ecash issuer. Each mint is tied to a specific currency unit (e.g. `sat` for satoshis, `usd` for US dollars). Only one mint can be active per unit at a time.

### Using the Web UI

1. Navigate to **Mints** in the sidebar.
2. Click **Create Mint**.
3. Fill in the form:
   - **Mint ID**: Click the dice icon to generate a UUID, or type your own.
   - **Display Name**: A human-readable name (e.g. "Production SAT Mint").
   - **Description**: Optional notes about this mint.
   - **Tags**: Optional comma-separated labels (e.g. `production, lightning`).
   - **Unit**: Select the currency unit (`sat`, `usd`, or `eur`).
   - **Denominations**: The token denominations this mint will support (e.g. `1,2,4,8,16,32,64`).
4. Click **Create**.

### Using the REST API

```bash
curl -X POST http://localhost:7778/admin/lifecycle/mints \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "mintId": "550e8400-e29b-41d4-a716-446655440000",
    "requestedBy": {
      "id": "123e4567-e89b-12d3-a456-426614174000",
      "displayName": "Alice Operator"
    },
    "metadata": {
      "displayName": "Production SAT Mint",
      "description": "Main satoshi mint for production",
      "tags": ["production", "lightning"]
    },
    "configuration": {
      "versionTag": "v1",
      "name": "prod-sat-mint",
      "cashu.unit": "sat",
      "cashu.denominations": "1,2,4,8,16,32,64"
    }
  }'
```

### Using the CLI

```bash
java -jar mint-admin-cli-*-runner.jar \
  --api-url http://localhost:7778 \
  --api-key local-dev-token \
  mint create \
  --mint-id 550e8400-e29b-41d4-a716-446655440000 \
  --operator-id 123e4567-e89b-12d3-a456-426614174000 \
  --version-tag v1
```

### What Happens Behind the Scenes

When you create a mint, several things happen automatically:

1. The mint is saved in the admin database with state **PROVISIONING**.
2. An outbox message is written in the same database transaction.
3. A background scheduler picks up the message and provisions the vault:
   - Creates the mint entity in the vault.
   - Generates a keyset for the specified unit and denominations.
   - Creates cryptographic keys for each denomination.
4. On success, the mint transitions to **PROVISIONED**.
5. On failure (after retries), the mint transitions to **PROVISION_FAILED** and partial vault state is cleaned up.

This process typically takes a few seconds. Refresh the mint detail page or re-query the API to see the updated state.

---

## Understanding the Mint Lifecycle

Every mint follows a strict lifecycle. The diagram below shows all possible states and transitions:

```
                    ┌──────────────────────┐
  POST /mints       │    PROVISIONING      │
  ─────────────────→│  (vault setup in     │
                    │   progress)          │
                    └──────┬───────┬───────┘
                           │       │
              success      │       │  failure (after retries)
                           ▼       ▼
                    ┌──────────┐  ┌──────────────────┐
                    │PROVISIONED│  │ PROVISION_FAILED │
                    │          │  │                  │
                    │ (ready   │  │ (cleanup done,   │
                    │  to go)  │  │  can retry)      │
                    └─────┬────┘  └──────────────────┘
                          │
             POST /resume │
                          ▼
                    ┌──────────┐
              ┌────→│  ACTIVE  │←────┐
              │     │          │     │
              │     │ (serving │     │
              │     │  ecash)  │     │
              │     └─────┬────┘     │
              │           │          │
  POST /resume│  POST /pause         │
              │           ▼          │
              │     ┌──────────┐     │
              └─────│SUSPENDED │─────┘
                    │          │
                    │(paused   │
                    │ for      │
                    │ maint.)  │
                    └──────────┘

  Any non-terminal state ──POST /retire──→  DECOMMISSIONED (terminal)
```

### State Descriptions

| State | Meaning | What you can do |
|-------|---------|----------------|
| **PROVISIONING** | Vault resources are being set up. | Wait. This is automatic. |
| **PROVISIONED** | Vault is ready. Mint is not yet serving ecash. | Activate (resume) or retire. |
| **PROVISION_FAILED** | Vault setup failed after all retries. | Investigate logs. Retry or retire. |
| **ACTIVE** | Mint is live and serving ecash requests. | Pause or retire. |
| **SUSPENDED** | Mint is paused (e.g. for maintenance). Ecash operations are blocked. | Resume or retire. |
| **DECOMMISSIONED** | Mint is permanently shut down. This is a terminal state. | Nothing. This is final. |

### Important Rules

- **One active mint per unit.** You cannot have two mints in `ACTIVE` state for the same unit (e.g. two `sat` mints). The system will reject the attempt with HTTP 409.
- **You cannot skip states.** A freshly provisioned mint must be activated before it can be paused. A mint in `PROVISIONING` cannot be activated until provisioning completes.
- **Retirement is permanent.** Once decommissioned, a mint cannot be reactivated. Create a new one instead.

---

## Day-to-Day Operations

### Viewing Your Mints

**Web UI:** Click **Mints** in the sidebar. Use the state dropdown to filter (e.g. show only `ACTIVE` mints). Click any row for details.

**REST API:**
```bash
# List all mints
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: MINT_ADMIN" \
     http://localhost:7778/admin/lifecycle/mints

# Filter by state
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: MINT_ADMIN" \
     "http://localhost:7778/admin/lifecycle/mints?state=ACTIVE"

# Get a specific mint
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: MINT_ADMIN" \
     http://localhost:7778/admin/lifecycle/mints/550e8400-e29b-41d4-a716-446655440000
```

### Activating a Mint

After provisioning completes (state = `PROVISIONED`), activate the mint to start serving ecash:

**Web UI:** Open the mint detail page and click **Activate**. Enter a reason and confirm.

**REST API:**
```bash
curl -X POST http://localhost:7778/admin/lifecycle/mints/550e8400-e29b-41d4-a716-446655440000/resume \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "reason": "Initial activation after provisioning"
  }'
```

### Pausing a Mint

Pause a mint to temporarily stop it from serving ecash (e.g. during maintenance):

**REST API:**
```bash
curl -X POST http://localhost:7778/admin/lifecycle/mints/550e8400-e29b-41d4-a716-446655440000/pause \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "reason": "Scheduled maintenance window"
  }'
```

### Resuming a Paused Mint

```bash
curl -X POST http://localhost:7778/admin/lifecycle/mints/550e8400-e29b-41d4-a716-446655440000/resume \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "reason": "Maintenance complete"
  }'
```

### Retiring a Mint

When a mint is no longer needed, decommission it permanently:

```bash
curl -X POST http://localhost:7778/admin/lifecycle/mints/550e8400-e29b-41d4-a716-446655440000/retire \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "reason": "Replaced by new mint with updated denominations"
  }'
```

---

## Managing Configuration

Each mint has a configuration — a set of key-value parameters that control its behavior. The admin module tracks every configuration change as a numbered **revision**, giving you a full history and the ability to roll back.

### Viewing Configuration History

**Web UI:** Open a mint, then click **Configuration** to see all revisions.

**REST API:**
```bash
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: MINT_ADMIN" \
     http://localhost:7778/admin/configuration/mints/550e8400-e29b-41d4-a716-446655440000/revisions
```

### Previewing Changes

Before applying a change, you can preview what the resulting configuration would look like:

```bash
curl -X POST http://localhost:7778/admin/configuration/mints/550e8400-e29b-41d4-a716-446655440000/preview \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "proposedConfiguration": {
      "cashu.expiry": "30",
      "cashu.denominations": "1,2,4,8,16,32,64,128"
    },
    "changeSummary": "Increase expiry and add 128 denomination"
  }'
```

This returns the merged configuration without saving anything.

### Applying Changes

```bash
curl -X POST http://localhost:7778/admin/configuration/mints/550e8400-e29b-41d4-a716-446655440000/apply \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "proposedConfiguration": {
      "cashu.expiry": "30",
      "cashu.denominations": "1,2,4,8,16,32,64,128"
    },
    "changeSummary": "Increase expiry and add 128 denomination"
  }'
```

### Rolling Back

If a configuration change causes problems, roll back to a previous revision:

```bash
curl -X POST http://localhost:7778/admin/configuration/mints/550e8400-e29b-41d4-a716-446655440000/rollback \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "targetRevisionId": 2
  }'
```

---

## Health Monitoring

Each mint has a health status that tells you whether it is operating normally.

| Status | Meaning |
|--------|---------|
| **HEALTHY** | Everything is working. |
| **DEGRADED** | The mint is operational but experiencing issues (e.g. slow responses). |
| **UNHEALTHY** | The mint is not functioning correctly. Investigate immediately. |
| **UNKNOWN** | Health status could not be determined. |

### Checking Health

**Web UI:** Open a mint and click **Health**.

**REST API:**
```bash
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: MINT_ADMIN" \
     http://localhost:7778/admin/health/mints/550e8400-e29b-41d4-a716-446655440000
```

Response:
```json
{
  "mintId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "HEALTHY",
  "lifecycleState": "ACTIVE",
  "checkedAt": "2026-02-17T10:30:00Z",
  "message": null
}
```

### Application Health Probes

The admin REST service also exposes standard Spring Boot health endpoints (no token required):

```bash
# Overall health
curl http://localhost:7778/actuator/health

# Readiness probe (for Kubernetes / Docker healthchecks)
curl http://localhost:7778/actuator/health/readiness
```

---

## Alerts

Alerts notify you of operational issues that need attention.

### Severity Levels

| Severity | When to use |
|----------|------------|
| **CRITICAL** | Immediate action required. Mint may be down or losing money. |
| **WARNING** | Something is wrong but not urgent. Investigate soon. |
| **INFO** | Informational. No action needed right now. |

### Viewing Alerts

**Web UI:** Click **Alerts** in the sidebar. Filter by severity or mint.

**REST API:**
```bash
# All alerts
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: ALERTS_ADMIN" \
     http://localhost:7778/admin/alerts

# Only critical alerts
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: ALERTS_ADMIN" \
     "http://localhost:7778/admin/alerts?severity=CRITICAL"

# Unacknowledged alerts for a specific mint
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: ALERTS_ADMIN" \
     "http://localhost:7778/admin/alerts?mintId=550e8400-e29b-41d4-a716-446655440000&acknowledged=false"
```

### Acknowledging an Alert

Acknowledging tells the team "I've seen this and I'm on it":

```bash
curl -X POST http://localhost:7778/admin/alerts/alert-001/acknowledge \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: ALERTS_ADMIN" \
  -d '{"requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}}'
```

### Silencing an Alert

If an alert is noisy and you need time to fix the underlying issue, silence it temporarily:

```bash
curl -X POST http://localhost:7778/admin/alerts/alert-001/silence \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: ALERTS_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "silenceMinutes": 60
  }'
```

### Escalating an Alert

When an alert needs attention from a specific team or external system:

```bash
curl -X POST http://localhost:7778/admin/alerts/alert-001/escalate \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: ALERTS_ADMIN" \
  -d '{
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"},
    "policyId": "oncall-engineering"
  }'
```

---

## Operator Management

Operators are the people who administer the mint. Each operator has an account with assigned roles.

### Creating an Operator

```bash
curl -X POST http://localhost:7778/admin/users \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: USER_ADMIN" \
  -d '{
    "userId": "bob-operator-001",
    "displayName": "Bob",
    "email": "bob@example.com",
    "roles": ["MINT_ADMIN", "ALERTS_ADMIN"],
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}
  }'
```

### Listing Operators

```bash
# Active operators only
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: USER_ADMIN" \
     http://localhost:7778/admin/users

# Include inactive operators
curl -H "X-Admin-Token: local-dev-token" \
     -H "X-Admin-Roles: USER_ADMIN" \
     "http://localhost:7778/admin/users?active=true"
```

### Assigning Roles

```bash
curl -X POST http://localhost:7778/admin/users/bob-operator-001/roles \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: USER_ADMIN" \
  -d '{"roles": ["MINT_ADMIN", "ALERTS_ADMIN", "OPS_ADMIN"]}'
```

### Resetting Credentials

```bash
curl -X POST http://localhost:7778/admin/users/bob-operator-001/reset-credentials \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: USER_ADMIN" \
  -d '{"requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}}'
```

This returns a temporary reset token and expiration time.

### Deactivating an Operator

```bash
curl -X POST http://localhost:7778/admin/users/bob-operator-001/deactivate \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: USER_ADMIN" \
  -d '{"requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}}'
```

---

## Operational Controls

Operational controls let you perform maintenance tasks and handle emergencies.

### Scheduling a Maintenance Window

Plan ahead by scheduling maintenance:

```bash
curl -X POST http://localhost:7778/admin/operations/mints/550e8400-e29b-41d4-a716-446655440000/maintenance/schedule \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: OPS_ADMIN" \
  -d '{
    "reason": "Database migration and key rotation",
    "durationMinutes": 30,
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}
  }'
```

### Starting and Completing Maintenance

```bash
# Start maintenance
curl -X POST http://localhost:7778/admin/operations/mints/550e8400-e29b-41d4-a716-446655440000/maintenance/start \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: OPS_ADMIN" \
  -d '{
    "reason": "Beginning scheduled migration",
    "durationMinutes": 30,
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}
  }'

# Complete maintenance
curl -X POST http://localhost:7778/admin/operations/mints/550e8400-e29b-41d4-a716-446655440000/maintenance/complete \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: OPS_ADMIN" \
  -d '{
    "reason": "Migration complete, all checks passed",
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}
  }'
```

### Key Rotation

Rotate the mint's cryptographic keys:

```bash
curl -X POST http://localhost:7778/admin/operations/mints/550e8400-e29b-41d4-a716-446655440000/keys/rotate \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: OPS_ADMIN" \
  -d '{
    "reason": "Quarterly key rotation per security policy",
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}
  }'
```

### Emergency Force-Close

In an emergency, you can immediately decommission a mint. This is irreversible:

```bash
curl -X POST http://localhost:7778/admin/operations/mints/550e8400-e29b-41d4-a716-446655440000/force-close \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: OPS_ADMIN" \
  -d '{
    "reason": "Security incident — compromised key material",
    "requestedBy": {"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "Alice"}
  }'
```

---

## Audit Trail

Every admin action is logged in the audit trail. This is read-only — you cannot modify or delete audit entries.

### Viewing the Audit Log

**Web UI:** Click **Audit** in the sidebar. Filter by mint, actor, or action type.

**REST API:**
```bash
# All events
curl -H "X-Admin-Token: local-dev-token" \
     http://localhost:7778/admin/audit/events

# Events for a specific mint
curl -H "X-Admin-Token: local-dev-token" \
     "http://localhost:7778/admin/audit/events?mintId=550e8400-e29b-41d4-a716-446655440000"

# Events by a specific operator
curl -H "X-Admin-Token: local-dev-token" \
     "http://localhost:7778/admin/audit/events?actor=123e4567-e89b-12d3-a456-426614174000"
```

Each audit entry contains:
- **eventId** — unique event identifier
- **mintId** — which mint was affected
- **actor** — who performed the action
- **action** — what was done (e.g. `CREATED`, `PAUSED`, `CONFIGURATION_APPLIED`)
- **timestamp** — when it happened
- **details** — additional context

---

## Dashboard

The dashboard provides a quick overview of your entire operation.

**Web UI:** The dashboard is the home page after login.

**REST API:**
```bash
curl -H "X-Admin-Token: local-dev-token" \
     http://localhost:7778/admin/dashboard/summary
```

Response:
```json
{
  "mintsByState": {
    "ACTIVE": 2,
    "SUSPENDED": 1,
    "PROVISIONED": 0,
    "DECOMMISSIONED": 3
  },
  "alertsBySeverity": {
    "CRITICAL": 0,
    "WARNING": 1,
    "INFO": 4
  },
  "activeControls": 1
}
```

---

## Common Workflows

### Replacing a Mint

To replace an existing mint with a new one (e.g. to change denominations):

1. **Pause** the old mint (stops new ecash operations).
2. **Create** a new mint with the updated configuration.
3. Wait for the new mint to reach **PROVISIONED**.
4. **Activate** the new mint.
5. **Retire** the old mint once all outstanding ecash has been redeemed.

### Handling Provisioning Failures

If a mint is stuck in `PROVISION_FAILED`:

1. Check the admin REST logs for the root cause:
   ```bash
   docker compose -f docker-compose.dev.yml logs cashu-mint-admin-rest
   ```
2. Common causes:
   - Vault service is unreachable.
   - Vault database is full or unresponsive.
   - Network connectivity between admin and vault containers.
3. Fix the underlying issue, then create a new mint (the failed one cannot be retried).
4. Retire the failed mint to clean up.

### Responding to a Critical Alert

1. **Acknowledge** the alert so the team knows someone is investigating.
2. Check the **health** status of the affected mint.
3. If the mint needs to be taken offline, **pause** it.
4. Investigate and fix the issue.
5. **Resume** the mint.
6. **Silence** the alert if it keeps firing while the fix propagates.

---

## Error Responses

When something goes wrong, the API returns a structured error:

```json
{
  "status": 409,
  "error": "Conflict",
  "code": "unit_conflict",
  "message": "cannot activate mint: another mint is already active for unit 'sat'"
}
```

### Common Error Codes

| Code | HTTP Status | What it means |
|------|-------------|---------------|
| `mint_not_found` | 404 | The mint ID does not exist. |
| `mint_already_exists` | 409 | A mint with this ID already exists. |
| `unit_conflict` | 409 | Another mint is already active for this unit. |
| `invalid_transition` | 400 | This state transition is not allowed (e.g. pausing a decommissioned mint). |
| `approval_required` | 403 | The transition requires additional sign-offs. |
| `configuration_conflict` | 409 | Someone else modified the configuration concurrently. |
| `user_not_found` | 404 | The operator ID does not exist. |
| `user_exists` | 409 | An operator with this ID already exists. |
| `alert_not_found` | 404 | The alert ID does not exist. |
| `unauthorized` | 401 | Missing or invalid admin token. |
| `forbidden` | 403 | Your role does not have permission for this action. |
| `invalid_request` | 400 | The request body failed validation. Check the message for details. |

---

## Configuration Reference

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` or `CASHU_MINT_ADMIN_PORT` | `7778` | REST API port |
| `DATASOURCE_URL` | `jdbc:postgresql://localhost:55435/cashu_admin` | Database connection string |
| `DATASOURCE_USERNAME` | `postgres` | Database user |
| `DATASOURCE_PASSWORD` | `postgres` | Database password |
| `ADMIN_API_TOKEN` | `local-dev-token` | Authentication token for admin API |
| `CASHU_VAULT_BASE_URL` | — | Vault service URL (e.g. `http://cashu-vault-jpa:3333`) |
| `LOG_LEVEL_ROOT` | `INFO` | Root logging level |
| `LOG_LEVEL_CASHU` | `DEBUG` | Cashu module logging level |

### Outbox Scheduler Settings

| Property | Default | Description |
|----------|---------|-------------|
| `admin.outbox.enabled` | `true` | Enable/disable background event processing |
| `admin.outbox.poll.interval` | `5000` | Milliseconds between poll cycles |
| `admin.outbox.batch.size` | `100` | Max messages processed per cycle |
| `admin.outbox.failure.backoff` | `PT30S` | Base retry delay (ISO-8601 duration) |
| `admin.vault.provision.max-retries` | `5` | Max provisioning attempts before giving up |

---

## Further Reading

- [REST API Reference](../reference/rest-api.md) — full endpoint specification
- [Architecture](../explanations/architecture.md) — how the admin module is structured internally
- [Transactional Outbox](../explanations/transactional-outbox.md) — how background event processing works
- [Configure Persistence](../how-to/configure-mint-admin-persistence.md) — database setup and migrations
- [Deploy the Web Interface](../how-to/deploy-web-production.md) — production deployment guide
