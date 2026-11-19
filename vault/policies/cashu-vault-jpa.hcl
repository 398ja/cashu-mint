# cashu-vault-jpa. Its only HashiCorp Vault call is KeyPublicKeyBackfill, a startup read of the
# private keys whose public key is not stored yet (cashu-vault 0.16.0). It never writes: the mint
# and mint-admin write keys through HCKeyVault in their own JVMs. No seeds, no delete, no metadata.
# @MOUNT@ is replaced by vault-init.
path "@MOUNT@/data/keys/*" {
  capabilities = ["read"]
}
