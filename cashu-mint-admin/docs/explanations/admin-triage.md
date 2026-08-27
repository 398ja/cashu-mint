# Triage: what in mint-admin actually works

Point-in-time assessment, 2026-08-19. Every administrative capability and every
surface, given one of three verdicts:

- **Actuates** — causes a real change outside this module.
- **Records only** — correctly changes the admin's own tables, and nothing else.
- **Decorative** — does not reliably do even that.

## How the admin reaches the mint

Not over HTTP. The admin and the mint **share the vault**. `CREATE` publishes an
outbox message; `VaultProvisioningOutboxHandler` consumes it and calls
`VaultProvisioningAdapter`, which uses the cashu-vault clients to store a mint
entity, a keyset and its key entries under deterministic ids — idempotent on
409, bounded retry, compensation on permanent failure. The mint then loads that
material at boot through `MintLoadService`.

Shared state is the integration point. Any future actuation should be weighed
against this pattern before reaching for a new transport.

## Verdicts

| Capability | Verdict | Evidence |
|---|---|---|
| Provisioning (`CREATED`) | **Actuates** | Full saga through `VaultProvisioningAdapter`; `PROVISIONING → PROVISIONED`, or compensated and failed. |
| Retire (`RETIRED`) | **Actuates**, but see below | `vaultPort.archive(mintId)` archives the mint's vault keysets. |
| Pause / Resume | Records only | `VaultProvisioningOutboxHandler` branches on `CREATED` and `RETIRED` only. `PAUSED` and `RESUMED` reach no handler. A paused mint keeps serving. |
| Configuration update | Records only | `CONFIGURATION_UPDATED` likewise has no handler branch. Stored, versioned, never applied. |
| Key rotation | Records only | `ExecuteOperationalControlsInteractor` returns the literal `"Key rotation initiated (placeholder)"` and writes one audit row — while the vault adapter it would need already exists a package away. |
| Maintenance windows, force-close | Records only | Same shape: audit rows, no effect. |
| Health monitoring | Decorative | `MonitorMintHealthInteractor.updateHealth` has exactly one caller in the repo — its own unit test. Health is permanently `UNKNOWN`. |
| Alerts / notifications | Decorative | Alerts exist only where an Operator posted them. No detection, and no delivery mechanism of any kind. |
| Access / RBAC | Decorative, and unsound | See below. |

## Retiring a mint does not stop it signing

This is the most serious functional defect, and it spans both modules.

`RETIRED` archives the mint's keysets in the vault. But in the mint, the
active/archived flag is **advertising metadata only**: `ActiveKeySetsTask` uses
it to populate `/v1/keysets`, `MintLoadService.keySets()` loads archived
keysets alongside active ones, and `SignBlindedMessageTask` resolves the private
key by whatever keyset id the *client* supplied. The string `active` appears
nowhere in the signing path.

So a retired mint keeps signing for any client that names the old keyset id.
The admin believes it has decommissioned a mint; the mint carries on issuing.

The same gap is why rotation cannot simply be built on the existing adapter:
writing a new keyset and archiving the old one would leave both signing.

## The security finding

**Closed by issues #370 and #373.** It is recorded here because the shape of the
fix explains the current design.

Access control used to read the caller's roles from an `X-Admin-Roles` request
header behind a single shared static token, so any token holder could assert
every role, and the operator store was never consulted at the enforcement point.
The audit trail recorded whichever operator id the caller supplied.

Operators now complete a NAP handshake with their Nostr key. `AdminAclResolver`
resolves their roles and permissions from the operator store on every request,
each controller names the permission it requires, and the audit actor is the
authenticated Operator. The shared token, the header and the credential reset
workflow no longer exist.

Unlike the gaps above, this is not a missing feature. It is a control that
appears to exist, has passing tests, and does not hold.

## Surfaces

| Surface | Verdict | Evidence |
|---|---|---|
| REST API | Sound | Genuinely wired to the core interactors. |
| Web UI | Sound client | Real API client per feature. Inherits the RBAC flaw by design. |
| CLI | Unsound | `MintAdminCliApplication:76-84` — connected mode wires one real port, `HttpMintLifecyclePort`. Status, config, users and alerts stay on `Stub*`. `StubMintAlertsPort` returns hardcoded rows including `"Lightning backend unreachable"`, presented as live data. |

## Where the tests stop short

The suite is green, and the gaps above survive it:

- `VaultProvisioningSagaIT` stubs `VaultProvisioningPort`, so it proves the saga's state transitions and not that anything reaches the vault.
- `MintProvisioningE2EIT` waits for the admin to report `PROVISIONED` and then calls the mint's `/v1/info`. It never asserts the mint can see or use the keyset just provisioned.
- `OperationalControlsE2EIT` asserts that rotation returned the string `"KEY_ROTATION_INITIATED"` — the admin echoing its own status, with nothing read from the mint.

The E2E stack boots a real mint next to the admin, so the assertions needed to
catch all three are available; they are simply not made.

## What is genuinely good

The hexagonal separation is real, the provisioning saga is properly built —
idempotency, bounded retry, compensation, deterministic ids — the lifecycle
state machine is enforced, the transactional outbox is correct, and the audit
trail captures actor, action, timestamp, request id and correlation id.

The problem is not the quality of what is here. It is that actuation stops after
provisioning and retirement, three capabilities are decorative, and one security
control does not hold.
