# cashu-mint-admin-rest: VaultProvisioningAdapter writes keyset keys through HCKeyVault.store, and
# VaultKeysetSeedSource reads its keyset master seed at startup (cashu-mint#545, #550).
# The seed is READ only: an operator creates it once (provision-vault.md, CAS 0), so mint-admin
# never writes it. "create" would not help anyway: vault-init sets cas_required on the path's
# metadata first, and Vault then treats any write there as an update. No other seeds/ path, no
# delete, no metadata. (generate-if-absent is for the dev compose, which uses a dev root token.)
path "@MOUNT@/data/keys/*" {
  capabilities = ["create", "read", "update"]
}
path "@MOUNT@/data/seeds/mint-admin/keyset-master-seed" {
  capabilities = ["read"]
}
