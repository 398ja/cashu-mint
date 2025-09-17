# Task: Model Access Control Policies and Audit Entries

## Background
Milestone M6 requires the domain layer to enforce access administration policies, including role hierarchies, delegation rules, and audit trails.

## Goal
Extend domain entities (`OperatorAccount`, roles, audit records) to capture identity provider metadata, RBAC policies, and immutable audit trails for access changes.

## Requirements
- Add value objects for role hierarchies, scoped entitlements, multi-factor requirements, and delegation expiration.
- Update `OperatorAccount` to store identity provider metadata, MFA settings, and delegated permissions while enforcing invariants.
- Persist immutable audit entries for access changes, linking them to lifecycle, configuration, and operational events.
- Emit domain events for provisioning, role updates, suspensions, and credential rotations.
- Write unit tests covering policy enforcement, delegation checks, and audit logging with plain-English comments above each test method.
- Document access control domain concepts for later adapters and compliance teams.

## Definition of Done
- Domain layer captures RBAC rules, identity metadata, and audit requirements with enforced invariants.
- Tests executed via `mvn -q verify` validate policy enforcement and event emission.
- Documentation summarises access policy modelling and audit structures.
