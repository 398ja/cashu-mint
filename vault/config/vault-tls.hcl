# Vault server configuration with TLS enabled on the listener.
#
# Use this instead of vault.hcl once you have a certificate. The certificate and key are expected
# at /vault/tls, which is a different mount from /vault/config on purpose: config is read-only,
# and certs typically come from a secret store or a generation step that needs to write.
#
#   docker compose -f docker-compose.prod.yml \
#     --set hashicorp-vault.command='vault server -config=/vault/config/vault-tls.hcl' up
#
# or bind-mount this file over vault.hcl. Remember to switch the healthcheck and VAULT_ADDR to
# https, and to set cashu.vault.hashi.url accordingly: a healthcheck speaking HTTP to an HTTPS
# listener never passes, which is its own way of never starting.
#
# See docs/how-to/provision-vault.md.

ui = false

storage "file" {
  path = "/vault/file"
}

listener "tcp" {
  address = "0.0.0.0:8200"

  tls_cert_file = "/vault/tls/vault.crt"
  tls_key_file  = "/vault/tls/vault.key"

  # TLS 1.2 floor. Vault's default is already this, stated so a future edit has to be deliberate.
  tls_min_version = "tls12"
}

# Disable mlock only if the platform forbids it; it stops secrets reaching swap. Requires the
# IPC_LOCK capability, which docker-compose.prod.yml grants. Managed platforms and rootless
# Docker often cannot, in which case set this to true and accept that secrets may be paged out.
disable_mlock = false

api_addr = "https://hashicorp-vault:8200"
cluster_addr = "https://hashicorp-vault:8201"
