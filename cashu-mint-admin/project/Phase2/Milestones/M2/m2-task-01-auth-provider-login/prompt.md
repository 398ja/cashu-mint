# Task: Build Authentication Provider and Login Experience

## Background
Milestone M2 introduces secure authentication, token handling, and session state management for the web client.

## Goal
Implement login UI, token acquisition/validation, session persistence, and inactivity handling aligned with admin REST contracts.

## Requirements
- Create dedicated authentication context/provider managing tokens, session state, and role metadata.
- Build login form with validation, integration to admin REST health endpoints, and secure clipboard copy for tokens.
- Implement session persistence in sessionStorage by default with opt-in "remember me" flows.
- Handle inactivity timers triggering warning modals and automatic logout after idle thresholds.
- Write unit/integration tests covering login success/failure, token storage fallbacks, and idle timeout handling with plain-English comments above each test method.
- Document authentication flows, storage decisions, and developer setup for local auth stubs.

## Definition of Done
- Authentication provider and login experience function end-to-end with tests executed via `mvn verify` (frontend pipeline).
- Tokens stored/cleared correctly, inactivity logic validated, and documentation updated.
- UI matches specification copy and accessible error messaging guidelines.
