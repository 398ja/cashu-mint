# Administrative Lifecycle Audit Schema

This reference describes the database tables and relationships used by the admin service to store lifecycle projections, approvals, and audit trails. Names are indicative; adapt to your schema conventions.

Tables:
- `t_admin_mint` — canonical mint record
  - `id UUID PK`, `name TEXT`, `state TEXT`, `version TEXT`, timestamps, archived
- `t_admin_lifecycle_event` — append-only lifecycle events
  - `id UUID PK`, `mint_id UUID FK -> t_admin_mint(id)`, `type TEXT` (CREATED/UPDATED/PAUSED/RESUMED/RETIRED), `operator_id UUID`, `request_id UUID`, `correlation_id UUID`, `metadata JSONB`, `created_at TIMESTAMP`
- `t_admin_approval` — approvals for actions requiring authorization
  - `id UUID PK`, `mint_id UUID FK`, `event_id UUID FK -> t_admin_lifecycle_event(id)`, `requested_by UUID`, `status TEXT` (PENDING/APPROVED/REJECTED), `decision_by UUID`, `decision_reason TEXT`, timestamps
- `t_admin_config_revision` — flattened config revisions for quick lookup
  - `id UUID PK`, `mint_id UUID FK`, `version TEXT`, `payload JSONB`, `created_at TIMESTAMP`
- `t_outbox` — transactional outbox for integration events
  - `id UUID PK`, `aggregate_id UUID`, `type TEXT`, `payload JSONB`, `published BOOLEAN`, timestamps

Indexes:
- `idx_lifecycle_mint_created_at` on (`mint_id`, `created_at`)
- `idx_approval_mint_status` on (`mint_id`, `status`)
- `idx_outbox_published_created_at` on (`published`, `created_at`)

Migrations:
- Flyway defaults: `classpath:db/migration-admin/{postgres|h2}` per Spring profile
- Baseline/validate strategies configured per environment

See the application configuration for profile-specific migration locations and Flyway/Liquibase settings.
