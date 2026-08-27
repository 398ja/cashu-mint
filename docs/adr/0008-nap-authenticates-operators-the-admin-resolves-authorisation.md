# NAP authenticates Operators; the admin resolves authorisation itself

The admin API authenticated callers with a single shared static token, and read
their roles from an `X-Admin-Roles` request header. ADR
[0005](0005-operators-authenticate-with-their-own-credential.md) replaced the
header with a per-Operator credential and server-side role resolution, but left
the shared token in place as a bootstrap credential — an empty operator store
had no other way in.

We adopted **NAP** (Nostr Authentication Protocol, `nap-spring` 0.6.1) for admin
authentication, and deleted the shared token along with the per-Operator
credential and its reset workflow. An Operator now proves possession of their
Nostr key over the NAP handshake and carries a session cookie; the deployment's
Super Administrator is named in configuration as an npub
(`admin.security.super-admin-npub`), which is what an empty operator store now
bootstraps from.

## Why the integration supplies a resolver rather than implementing NAP's ACL store

NAP ships an `AclStore` SPI and a JDBC implementation, alongside the simpler
`AclResolver` seam. We implement `AclResolver`
(`AdminNapConfiguration#adminAclResolver`) and leave `AclStore` alone.

The admin already owns an operator store: `admin_users`, with roles, an active
flag, and a `pubkey` column, reached through `OperatorAccessRepository` and
written by the use cases that also record the Audit Trail. Implementing
`AclStore` would make NAP a second writer over the same facts, and its API
cannot express the ones that matter here — it grants and revokes, but does not
model a suspended-but-retained Operator (the Audit Trail names Operators, so
their rows have to survive suspension), and it has no notion of an entitlement
that comes from configuration rather than from a row, which is exactly what the
Super Administrator is. A resolver is a read: NAP asks what an authenticated
npub may do, and the admin answers from the store it already keeps consistent.

## NAP's ACL table is created and deliberately unused

NAP's Flyway migrations run against the `nap` schema (`AdminNapConfiguration.Stores`),
so its ACL table is created alongside the challenge and session tables the admin
does use. It stays empty on purpose. It is not dead weight left behind by a
half-finished migration, and the next upstream bump should not drop it: it comes
with NAP's own migrations, and removing it would mean forking them.

## Consequences

- No credential lives in the admin's database. There is nothing to reset, and no
  bootstrap secret to rotate or leak.
- The Super Administrator is a deployment fact. `adminAclResolver` refuses to
  start when the npub is unset or malformed, rather than starting with an admin
  nobody can reach.
- Authorisation stays one bean. Roles resolve from `admin_users`; permissions
  resolve from the role vocabulary in `mint-admin-core`; the browser is told
  what it may do and never derives it.
