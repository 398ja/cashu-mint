# Task: Enforce Security Hardening Across CLI and REST

## Background
Milestone M8 emphasises security readiness: authentication, authorisation, TLS, rate limiting, and secret management must be production-grade.

## Goal
Implement security enhancements including OAuth2/JWT flows, mutual TLS (where required), rate limiting, and vault integration across CLI and REST adapters.

## Requirements
- Enforce OAuth2/JWT authentication with token validation, role scopes, and CLI integration for token retrieval.
- Configure mutual TLS and certificate rotation workflows for service-to-service communication where mandated.
- Introduce rate limiting, request throttling, and abuse detection for REST endpoints.
- Integrate vault secrets for CLI and service credentials, ensuring rotation procedures and audit logging.
- Perform security testing (static analysis, dependency scanning, penetration-style integration tests) with plain-English comments above each test method.
- Update security documentation, threat models, and runbooks describing hardening measures and operational controls.

## Definition of Done
- Security controls enforced across CLI and REST with automated tests executed via `mvn -q verify` and security scanning pipelines.
- Documentation and runbooks outline authentication flows, TLS setup, rate limiting, and secret handling.
- Compliance stakeholders sign off on security posture for release readiness.
