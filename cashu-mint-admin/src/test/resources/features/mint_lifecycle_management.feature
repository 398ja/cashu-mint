Feature: Manage mint lifecycle
  In order to keep Cashu mints reliable and auditable
  As an authorized mint administrator
  I want to orchestrate lifecycle changes with consistent configuration history and event publishing

  Background:
    Given a mint administrator identified as "operator-123" is authorized to manage lifecycle actions
    And lifecycle commands capture request metadata for operational observability

  Scenario: Provisioning a new mint
    Given no mint exists with ID "mint-001"
    When the administrator registers mint "mint-001" with version tag "v1.0.0"
      And provides request ID "req-001" and correlation ID "mint-001-onboarding"
    Then the mint is stored with lifecycle state "PROVISIONED"
      And configuration revision "1" stores the version tag "v1.0.0"
      And a "CREATED" lifecycle event is published for mint "mint-001"
      And the audit log entry records operator "operator-123" with request ID "req-001" and correlation ID "mint-001-onboarding"

  Scenario: Updating a mint configuration revision
    Given mint "mint-001" exists with lifecycle state "PROVISIONED" and configuration revision "1"
      And version tag "v1.0.0" was last applied
    When the administrator updates the configuration to version tag "v1.1.0"
      And provides request ID "req-002" and correlation ID "mint-001-update"
    Then the configuration revision advances to "2" with version tag "v1.1.0"
      And the mint lifecycle state remains "PROVISIONED"
      And a "CONFIGURATION_UPDATED" lifecycle event is published with configuration revision "2"
      And the audit log entry links request ID "req-002" and correlation ID "mint-001-update" to the change

  Scenario Outline: Changing the mint lifecycle state
    Given mint "mint-001" exists with lifecycle state "<current state>" and configuration revision "2"
      And an audit entry already records version tag "<existing version>"
    When the administrator issues the "<command>" lifecycle command with version tag "<version tag>"
      And provides request ID "<request id>" and correlation ID "<correlation id>"
    Then the mint lifecycle state becomes "<next state>"
      And a "<event type>" lifecycle event is published showing the previous state "<previous state>" and current state "<next state>"
      And the audit log entry captures request ID "<request id>" and correlation ID "<correlation id>"

    Examples:
      | command | current state | previous state | next state     | version tag  | existing version | request id | correlation id        | event type |
      | RESUME  | PROVISIONED   | PROVISIONED    | ACTIVE         | resume-ops   | v1.1.0           | req-003    | mint-001-activation   | RESUMED    |
      | PAUSE   | ACTIVE        | ACTIVE         | SUSPENDED      | pause-window | resume-ops       | req-004    | mint-001-maintenance  | PAUSED     |
      | RETIRE  | SUSPENDED     | SUSPENDED      | DECOMMISSIONED | retire-final | pause-window     | req-005    | mint-001-decommission | RETIRED    |

  Scenario: Rejecting invalid lifecycle transitions
    Given mint "mint-001" exists with lifecycle state "PROVISIONED"
    When the administrator issues the "PAUSE" lifecycle command with version tag "ops-window"
    Then the lifecycle command is rejected
      And an error message lists the allowed transitions from "PROVISIONED"
      And no lifecycle event is published

  Scenario: Generating identifiers when metadata is omitted
    Given mint "mint-002" does not exist yet
    When the administrator registers mint "mint-002" with version tag "v1.0.0"
      And omits request and correlation identifiers
    Then the service generates a unique request ID and correlation ID for the audit entry
      And the generated correlation ID matches the generated request ID
      And a "CREATED" lifecycle event is published containing the generated identifiers
