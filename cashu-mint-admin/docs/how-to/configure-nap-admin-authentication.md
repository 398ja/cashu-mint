# Configure NAP admin authentication

How to name the Super Administrator for a deployment, enrol the first
Administrator, and choose a signer. Operators authenticate by proving possession
of a Nostr key over the NAP handshake — there is no admin password or shared
token. The reasoning is in
[ADR 0008](../../../docs/adr/0008-nap-authenticates-operators-the-admin-resolves-authorisation.md).

## Configure the Super Administrator

The Super Administrator is named in configuration, not stored as a row. Set
their npub before first start:

```properties
admin.security.super-admin-npub=npub1...
nap.enabled=true
```

or, as an environment variable, `ADMIN_SUPER_ADMIN_NPUB` (the compose
stacks pass it through as `CASHU_MINT_ADMIN_SUPER_ADMIN_NPUB`).

Use the npub of a key the operator team can still sign with if every other way
in is lost — this is the account that recovers the deployment. It holds every
permission, cannot be suspended or edited through the admin API, and appears in
the Operator listing marked *configuration-anchored*.

### If it is missing or malformed

With `nap.enabled=true`, the admin **fails to start**.
`AdminNapConfiguration#adminAclResolver` throws at context refresh:

- unset or blank →
  `admin.security.super-admin-npub is not set. NAP cannot start without a Super
  Administrator: no other npub can grant the first operator a role.`
- not a valid npub (wrong prefix, bad checksum, a hex key, an `nsec`) →
  an `IllegalStateException` carrying the decoding failure.

Both are startup failures rather than bad requests, on purpose: an admin that
started with no reachable Super Administrator would accept sign-ins and grant
nobody anything.

The resolver is conditional on `nap.enabled`, so this check is too: an admin
started with `nap.enabled=false` has no authentication at all and will not
complain about a missing npub. Never run a deployment that way.

## Enrol the first Administrator

1. Sign in as the Super Administrator at the admin UI (see *Choose a signer*).
2. Open **Operators** in the navigation.
3. Enter the new Operator's display name and npub, pick their role, and submit.
   The npub is checked in the browser, so a mistyped key is refused in the form.
4. The Operator signs in with their own key. No credential is issued, sent, or
   displayed — there is nothing to hand over.

Roles carry permissions: `MINT_ADMIN` (mint lifecycle, audit, dashboard),
`USER_ADMIN` (manage Operators, dashboard), `OPS_ADMIN` (operational controls,
dashboard). `SUPER_ADMIN` cannot be assigned here; it comes from configuration.

To suspend an Operator, use the **Suspend** control on the same page. The row
survives — the Audit Trail names Operators, so they stay resolvable — and the
resolver refuses them, and drops their live sessions, on their next request.
**Reinstate** restores the access their roles carry.

## Choose a signer

The admin never sees a private key. Three ways to hold one:

| Signer | Choose it when |
|---|---|
| **NIP-07 browser extension** (Alby, nos2x, …) | The default, and the right answer for a day-to-day Operator on their own workstation. The key never enters the admin page; the extension prompts for each signature. |
| **Encrypted key in this browser** | No extension is available or allowed — a locked-down machine, a shared kiosk, a first sign-in before tooling is installed. The key is stored encrypted in the browser and unlocked with a passphrase per session, so it is only as safe as that browser profile. Prefer an extension where you can install one. |
| **NIP-46 remote signer (bunker)** | The key must stay on a separate device or a hardware signer, and every signature should be approved there. *Not yet implemented in the admin UI — see issue #377.* |

The login page offers the extension when `window.nostr` is present, and the
in-browser key otherwise.

## Verify

```bash
curl -s -b "$COOKIE" http://localhost:8080/api/v1/auth/session | jq
```

A signed-in Super Administrator answers with `roles: ["SUPER_ADMIN"]` and the
full permission list. Without a session, `/admin/**` answers 401 with the same
error shape as any other admin refusal.
