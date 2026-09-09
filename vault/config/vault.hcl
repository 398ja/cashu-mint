# HashiCorp Vault server configuration for the production stack.
#
# This replaces `-dev` mode, which the 2026-09-05 audit flagged (H-9). Dev mode is unsealed,
# in-memory and HTTP-only, and it accepts a root token from an environment variable, so a
# dev-mode Vault holding the mint's signing keys is equivalent to no protection at all.
#
# Before first use:
#   1. `docker compose -f docker-compose.prod.yml up -d hashicorp-vault`
#   2. `docker compose exec hashicorp-vault vault operator init`   (record the unseal keys and
#      the initial root token OFFLINE; split the unseal keys between separate key holders)
#   3. `docker compose exec hashicorp-vault vault operator unseal` (repeat to the threshold)
#   4. Use the root token once as VAULT_INIT_TOKEN for the `vault-init` job, then revoke it:
#      `vault token revoke <token>`
#
# Vault seals again on every restart. For unattended restarts configure an auto-unseal seal
# stanza (AWS KMS, GCP KMS, Azure Key Vault, or Transit against a second Vault) rather than
# reverting to dev mode.

ui = false

storage "file" {
  path = "/vault/file"
}

listener "tcp" {
  address = "0.0.0.0:8200"

  # TLS terminates at the container boundary, and this listener is not published: the port is
  # only reachable from the private `cashu` network by the mint and the vault service.
  #
  # This previously pointed at /vault/config/tls/vault.crt and vault.key, which do not exist in
  # this repository, have no generation step, and could not be created at runtime because
  # ./vault/config is mounted read-only. Vault exits immediately on a missing cert, and with
  # restart: unless-stopped that is a crash-loop; vault-init waits on service_healthy, the vault
  # service waits on vault-init, and the mint waits on that, so the entire production stack
  # failed to start. A security hardening that stops the system booting is not hardening.
  #
  # To enable TLS: generate a cert and key, mount them at /vault/tls (a SEPARATE mount, so it can
  # be writable or come from a secret store while this config stays read-only), and use
  # vault-tls.hcl instead of this file. See docs/how-to/provision-vault.md.
  tls_disable = 1
}

# Disable mlock only if the platform forbids it; it stops secrets reaching swap.
disable_mlock = false

api_addr = "http://hashicorp-vault:8200"
# Deliberately not set. Vault's cluster port always speaks TLS using its own internally generated
# certificates, independently of the API listener, so it reports as https even here; setting it to
# http would be ignored rather than honoured. Unused anyway with single-node file storage.
