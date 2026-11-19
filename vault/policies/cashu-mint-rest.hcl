# cashu-mint-rest: signing reads private keys (HCKeyVault.enrichWithVaultSecret), and
# VaultPreloadSeeder writes a seed keyset through HCKeyVault.store (a read, then a check-and-set
# put). No delete, no metadata, no seeds.
path "@MOUNT@/data/keys/*" {
  capabilities = ["create", "read", "update"]
}
