# Provision HashiCorp Vault for the mint

The mint's keyset private keys live in Vault. This is the first-boot sequence, which is not
something `docker compose up` can do on its own: a fresh Vault is uninitialised and sealed, and
unsealing it is deliberately a human act.

Referenced from `vault/config/vault.hcl` and `docker-compose.prod.yml`.

## Why this is not automatic

Vault's initialisation produces the unseal keys and the initial root token. If a script held
those, the script would be the trust anchor, and storing them anywhere the stack can reach makes
the seal decorative. So the stack brings Vault up, and you initialise and unseal it by hand.

The `vault-init` job is separate from that. It provisions the KV mount, the least-privilege
policy and the AppRole the application authenticates with. It needs an already-unsealed Vault and
a privileged token that you supply for the duration of the run and revoke afterwards.

## Sequence

### 1. Start Vault alone

```bash
docker compose -f docker-compose.prod.yml up -d hashicorp-vault
```

The container is healthy once it answers on `/v1/sys/health`, which it does while still sealed.
That is intentional: health here means "serving and reachable". Readiness to hand out secrets is
what the next steps establish.

### 2. Initialise

```bash
docker compose -f docker-compose.prod.yml exec hashicorp-vault \
  vault operator init -key-shares=5 -key-threshold=3
```

This prints five unseal keys and an initial root token, once. Distribute the unseal keys to
separate people or separate custody, and store the root token only until step 4 is done.

### 3. Unseal

Three different key holders, three invocations:

```bash
docker compose -f docker-compose.prod.yml exec hashicorp-vault vault operator unseal
```

Repeat after any restart of the Vault container. Consider auto-unseal against a cloud KMS if that
is unacceptable operationally; it moves the trust anchor rather than removing it.

### 4. Provision the mount, the per-client policies and AppRoles

```bash
VAULT_INIT_TOKEN=<the root token from step 2> \
  docker compose -f docker-compose.prod.yml run --rm vault-init
```

It writes one policy and one AppRole per client (cashu-mint#550), each with only the verbs that
client uses:

| AppRole | Policy file | Can |
|---|---|---|
| `cashu-vault-jpa` | `vault/policies/cashu-vault-jpa.hcl` | read `keys/*` |
| `cashu-mint-rest` | `vault/policies/cashu-mint-rest.hcl` | create, read, update `keys/*` |
| `cashu-mint-admin` | `vault/policies/cashu-mint-admin.hcl` | create, read, update `keys/*`; create and read its keyset seed, never update it |

No client can delete or destroy a key, list the mount, read another `seeds/` path or touch
`sys/`. The seed path also gets `cas_required=true`. If the Vault still has the old shared
`cashu-vault` policy and role, vault-init deletes them.

Each client's role-id and secret-id land in its own directory of the `vault-approle` volume
(`/vault/approle/<role>/vault.hashi.auth.approle.{role-id,secret-id}`). Each service mounts only its
own directory (a volume `subpath`) and reads it with `SPRING_CONFIG_IMPORT=configtree:`, so no
credential is copied into `.env` and none is printed. The secret-id is reusable: the services log
in again after every restart and whenever their token reaches `token_max_ttl` (24 h), which needs a
cashu-vault-hashi that renews AppRole tokens. Re-running vault-init issues new secret-ids and
destroys the old ones; restart the three services afterwards.

### 4b. Create mint-admin's keyset master seed (once)

```bash
docker compose -f docker-compose.prod.yml exec -e VAULT_TOKEN=<the root token> hashicorp-vault \
  sh -c 'vault kv put -mount=cashu -cas=0 seeds/mint-admin/keyset-master-seed seed="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d " \n")" >/dev/null'
```

`-cas=0` succeeds only if the path was never written, so this cannot replace an existing seed.
mint-admin refuses to start until it exists.

Now revoke the root token from step 2:

```bash
docker compose -f docker-compose.prod.yml exec hashicorp-vault \
  vault token revoke <the root token>
```

### 5. Start the rest of the stack

```bash
docker compose -f docker-compose.prod.yml up -d
```

## TLS

The default `vault/config/vault.hcl` sets `tls_disable = 1`. The Vault port is not published and
is reachable only by the mint and the vault service on the private `cashu` network, so TLS
terminates at the container boundary.

To terminate TLS at Vault itself, use `vault/config/vault-tls.hcl`, which expects a certificate
and key at `/vault/tls` (a separate mount from the read-only `/vault/config`, so certs can come
from a secret store or a generation step that needs to write). Then switch **all** of these
together, because a healthcheck speaking the wrong scheme never passes and every dependent
service waits for ever:

- the `hashicorp-vault` healthcheck URL
- `VAULT_ADDR` on `hashicorp-vault` and `vault-init`
- `VAULT_HASHI_URI` on `cashu-vault-jpa`
- `api_addr` in the hcl file

## Troubleshooting

**Vault exits immediately on start.** Check the config parses and, if using `vault-tls.hcl`, that
the certificate files exist at `/vault/tls`. Vault refuses to start on a missing cert, and with
`restart: unless-stopped` that presents as a crash-loop.

**`Failed to lock memory`.** `disable_mlock = false` needs the `IPC_LOCK` capability, which the
compose file grants. On a platform that cannot grant it (many managed container services,
rootless Docker), set `disable_mlock = true` and accept that secrets may be paged to swap.

**Every service is stuck waiting.** `vault-init` gates on Vault being healthy, `cashu-vault-jpa`
gates on `vault-init` completing, and `cashu-mint-rest` gates on that. Check Vault is unsealed and
healthy first; the rest of the chain follows.

**The application logs a Vault authentication failure.** Check that
`VAULT_HASHI_AUTH_METHOD=approle` is lowercase, that the service mounts its own directory of the
`vault-approle` volume at `/run/secrets/vault-approle`, and that vault-init has run since the
secret-ids were last rotated.

**A service logs 403 on a key or the seed.** Its policy does not grant that verb. Compare the call
with `vault/policies/<role>.hcl`; widen the policy only for a verb the code actually uses.
