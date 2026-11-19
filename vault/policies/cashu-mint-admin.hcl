# cashu-mint-admin-rest: VaultProvisioningAdapter writes keyset keys through HCKeyVault.store, and
# VaultKeysetSeedSource reads its keyset master seed at startup (cashu-mint#545, #550).
# "create" without "update" on the seed: mint-admin can create it on a path never written
# (generate-if-absent, CAS 0) but can never replace it, whatever CAS value it sends. vault-init
# also sets cas_required on the path. No other seeds/ path, no delete, no metadata.
path "@MOUNT@/data/keys/*" {
  capabilities = ["create", "read", "update"]
}
path "@MOUNT@/data/seeds/mint-admin/keyset-master-seed" {
  capabilities = ["create", "read"]
}
