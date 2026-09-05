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

  # TLS is expected. Mount certificates into /vault/config and point these at them. Only set
  # tls_disable = 1 when Vault sits behind a mesh or proxy that terminates TLS and the container
  # network is genuinely private; the mint's signing keys travel over this connection.
  tls_cert_file = "/vault/config/tls/vault.crt"
  tls_key_file  = "/vault/config/tls/vault.key"
}

# Disable mlock only if the platform forbids it; it stops secrets reaching swap.
disable_mlock = false

api_addr = "https://hashicorp-vault:8200"
