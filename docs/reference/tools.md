# Tools Reference

The `cashu-mint-tools` module generates deterministic preload data: a reproducible
mint id, keyset and set of private keys, derived from a fixed algorithm so the same
inputs always yield the same keyset id.

## Generating preload JSON

```bash
./mvnw -q -pl cashu-mint-tools -Ppreload-json exec:java
```

This writes `scripts/preload-test-data.json`, which the mint's `VaultPreloadSeeder`
reads at startup to seed the shared vault: the keyset row goes to the vault
database and the private keys go to HashiCorp, which is why seeding happens through
the mint rather than by loading SQL.

## Defaults and overrides

Defaults come from `cashu-mint-tools/mint-preload.properties`. Override with `-D`:

```bash
-Dmint.preload.mint-id=$(uuidgen)
-Dmint.preload.json.output=target/preload.json
```

## The SQL renderer is obsolete

`MintPreloadSqlRenderer`, the `preload-sql` and `preload-all` profiles, and
`scripts/render-preload-sql.sh` emit `INSERT`s against `t_key.private_key` — a
column dropped when key material moved to HashiCorp Vault and was replaced by
`vault_path`. Running them produces SQL the vault database rejects.

Key material cannot be seeded over SQL any more: only the backend-aware vault
client knows where a secret goes and how to stamp the row that points at it. Use
the JSON path above.
