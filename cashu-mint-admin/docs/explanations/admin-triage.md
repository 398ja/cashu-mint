# Triage: what in mint-admin actually works

Point-in-time assessment, revised 2026-08-28. Every administrative capability and every
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

This only holds when the mint actually reads that vault. `PreloadMintLoadService`
is `@Primary` and on by default, serving a fixed keyset from JSON; a deployment
that leaves it enabled shares a vault with the admin and still disagrees with it.
Both the dev and E2E stacks therefore set `MINT_PRELOAD_ENABLED=false`.

## Verdicts

| Capability | Verdict | Evidence |
|---|---|---|
| Provisioning (`CREATED`) | **Actuates** | Full saga through `VaultProvisioningAdapter`; `PROVISIONING → PROVISIONED`, or compensated and failed. |
| Retire (`RETIRED`) | **Actuates**, but see below | `vaultPort.archive(mintId)` archives the mint's vault keysets. |
| Pause / Resume | Records only, but the mint is ready | The mint honours suspension durably: `MintSuspensionEntity` is read on the issuance path and a suspended mint refuses to issue with `mint_suspended` while still honouring swaps and melts (ADR-0006, ADR-0007). What is missing is the write — `VaultProvisioningOutboxHandler` branches on `CREATED`, `RETIRED` and `KEYS_ROTATED` only, so nothing in the admin ever sets that state. A mint paused in the admin keeps issuing. |
| Configuration update | Records only | `CONFIGURATION_UPDATED` likewise has no handler branch. Stored, versioned, never applied. |
| Key rotation | **Actuates** | `ROTATE_KEYS` writes an outbox message in the same transaction as the control row; `VaultProvisioningOutboxHandler` drives `VaultProvisioningAdapter.rotate`, which archives the outgoing keyset, provisions the replacement, and records which keyset replaced which. |
| Maintenance windows, force-close | Records only | Same shape: audit rows, no effect. |
| Access / RBAC | **Sound** | Closed by #370 and #373: every controller names the permission it requires, Operators authenticate through NAP, and `SUPER_ADMIN` is configuration rather than data. See the security finding below. |

Health monitoring and alerts were removed rather than fixed; both were decorative,
with no detection and no delivery. Their endpoints and domain types are gone.

## Retiring a mint stops it signing

**Closed.** This was once the most serious functional defect here: `RETIRED`
archived the mint's keysets, but the archived flag was advertising metadata only —
`SignBlindedMessageTask` resolved the key by whatever keyset id the *client*
supplied, so a retired mint kept signing for anyone naming the old id.

`MintProtocolUtil.getPrivateKeyForSigning` now refuses an archived keyset with
`keyset_inactive`, so archiving genuinely retires a keyset for issuance while
redemption paths keep verifying it (ADR-0004). Rotation is built on that, and the
vault reinforces it by permitting one active keyset per unit.

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
| Web UI | Sound | Real API client per feature, and the login page completes a NAP handshake against the operator's key. |

The CLI was removed rather than fixed. Its connected mode wired exactly one real
port and served hardcoded rows — including `"Lightning backend unreachable"` — as
though they were live data.

## Where the tests stop short

Largely addressed. `KeyRotationE2EIT` now asserts rotation against the mint's own
`/v1/keysets` rather than against the admin's echo, which is only meaningful because
the stack runs a vault-backed mint (`MINT_PRELOAD_ENABLED=false`).

What remains:

- `VaultProvisioningSagaIT` stubs `VaultProvisioningPort`, so it proves the saga's
  state transitions and not that anything reaches the vault.
- `MintProvisioningE2EIT` waits for the admin to report `PROVISIONED` and then calls
  the mint's `/v1/info`. It never asserts the mint can use the keyset just
  provisioned.

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
