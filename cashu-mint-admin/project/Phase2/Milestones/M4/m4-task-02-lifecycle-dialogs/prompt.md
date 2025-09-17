# Task: Build Lifecycle Action Dialogs and Preview Flows

## Background
Operators must perform create/update/pause/resume/retire actions via multi-step dialogs with previews and confirmations before submission.

## Goal
Implement lifecycle action steppers, preview integration, optimistic updates, and audit annotation capture for `MINT_ADMIN` users.

## Requirements
- Develop multi-step dialogs for each lifecycle action capturing inputs, previewing backend responses, and confirming with audit summaries.
- Integrate with preview endpoints, handling success/error responses and presenting actionable guidance.
- Apply optimistic updates with reconciliation after API responses, including partial failure handling.
- Enforce role guards disabling actions for unauthorised operators and providing contextual messaging.
- Write integration tests covering each lifecycle flow, preview errors, and optimistic update reconciliation; annotate each test method with plain-English comments.
- Update Storybook with dialog states for design review.

## Definition of Done
- Lifecycle dialogs execute previews and confirmations successfully with tests executed in `mvn verify`.
- Optimistic updates reconcile correctly, capturing audit annotations for documentation.
- Documentation describes lifecycle UI flows, validations, and error handling patterns.
