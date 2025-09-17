# Task: Integrate Telemetry Ingestion and Storage Infrastructure

## Background
Milestone M4 depends on reliable telemetry ingestion, storage, and retention pipelines. Framework integrations must align with OpenTelemetry/Prometheus guidance and support historical analysis.

## Goal
Configure telemetry collectors, persistence mechanisms, and background schedulers that feed health data into the admin datastore with retention policies.

## Requirements
- Integrate metrics exporters (OpenTelemetry, Prometheus) to gather mint-level observations, alerts, and incidents.
- Provision time-series storage (TimescaleDB, hypertables, or external TSDB) with retention and pruning rules.
- Implement ingestion adapters and batching logic that normalise telemetry data for the interactor.
- Configure background schedulers for periodic evaluation, historical aggregation, and export to external observability stacks.
- Add integration tests or smoke tests validating ingestion pipelines and storage queries, annotated with plain-English comments above each test method.
- Document deployment considerations, storage sizing, and retention strategies for telemetry infrastructure.

## Definition of Done
- Telemetry ingestion pipeline deployed, storing data accessible to health read models and dashboards.
- Tests under `mvn -q verify` (or supplemental integration suites) validate ingestion and retention behaviours.
- Runbooks describe telemetry infrastructure, scaling, and monitoring steps.
