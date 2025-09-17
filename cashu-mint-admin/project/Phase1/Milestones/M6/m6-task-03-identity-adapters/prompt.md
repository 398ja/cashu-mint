# Task: Integrate Identity Providers and Vault-backed Credential Storage

## Background
Milestone M6 must connect to external identity providers while securely handling service account tokens and secrets via the vault port.

## Goal
Implement adapters for OIDC/SAML/JWT authentication, configure vault-based credential storage, and ensure secure token lifecycle management.

## Requirements
- Build adapters for at least one external identity provider (OIDC/JWT), including token validation, group claims mapping, and metadata refresh.
- Implement secure storage for CLI/API tokens leveraging the vault port, including rotation hooks and audit logging.
- Provide configuration profiles supporting local mocks and production identity integrations.
- Enforce MFA and session policies by integrating with interactor validation.
- Write integration tests covering token verification, vault interactions, and failure handling with plain-English comments above each test method.
- Document identity provider setup, token rotation procedures, and vault configuration for operators.

## Definition of Done
- Identity and vault adapters authenticate and store credentials securely, validated by tests executed via `mvn -q verify` (or targeted integration suites).
- Configuration and documentation enable teams to integrate additional providers with minimal effort.
- Audit logs capture identity events and credential operations for compliance review.
