# Verify a new mint gets a NUT-02 v2 keyset

This guide provisions a brand-new mint and checks that the keyset it derives is a
[NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md) **v2** keyset committing to the mint's
input fee.

Provisioning is the case worth checking. Rotation replaces a keyset that already exists, so it
cannot show that *establishing* a mint produces a v2 id, and a seeded fixture proves nothing about
what the code derives.

## Before you start

- The admin API reachable at some `BASE_URL` (over an SSH tunnel for a remote host).
- An operator nsec authorised to call `POST /admin/lifecycle/mints`.
- Read access to the vault database, to inspect the derived keyset.

## Provision a mint

`tools/provision-mint/ProvisionMint.java` opens a NAP session, creates a mint with the input fee
you name, and prints the new mint id.

```bash
CP="cashu-mint-admin/mint-admin-tests/integration-tests/target/classes:$(cat nap-cp.txt)"
javac -proc:none -cp "$CP" -d /tmp/provision tools/provision-mint/ProvisionMint.java
java -cp "$CP:/tmp/provision" ProvisionMint \
  "$BASE_URL" "$EXTERNAL_BASE_URL" "$PRIV_HEX" "$NPUB" 100
```

It prints `MINT_ID=<uuid>`. Provisioning then completes asynchronously through the outbox, so allow
a few seconds before looking for the keyset.

## Check the derived keyset

```sql
SELECT key_set_id, unit, input_fee_ppk, length(key_set_id) AS len
FROM t_keyset WHERE mint_id = '<uuid>';
```

A v2 keyset id is the version byte `01` followed by a SHA-256 digest in hex, so a correct result
starts with `01` and is 66 characters long:

```
                              key_set_id                            | unit | input_fee_ppk | len
--------------------------------------------------------------------+------+---------------+-----
 01fef5f01800f43f88adc5c1531cb47c2ecb23431d9b5557da3b8b62fcb94fe959 | sat  |           100 |  66
```

A 16-character id beginning `00` is a v1 keyset, and means the mint is serving a seeded or legacy
keyset rather than one it derived.

## Confirm the fee is bound into the id

Under v2 the input fee is part of the hashed material, so two mints that differ only in fee must
advertise different ids. Provision a second mint with a different fee and compare:

```bash
java -cp "$CP:/tmp/provision" ProvisionMint "$BASE_URL" "$EXTERNAL_BASE_URL" "$PRIV_HEX" "$NPUB" 0
```

If both mints report the same id, the fee is not reaching the derivation, and a mint charging a fee
is indistinguishable from one that does not. `GET /v1/keysets` should show each id against its own
`input_fee_ppk`.

## Troubleshooting

**The mint is created but no keyset appears.** Provisioning runs in an outbox that retries and then
gives up, so a failure is quiet. Check the mint-admin log for `Vault provisioning failed`, and the
vault log for the underlying cause. A `value too long for type character varying(16)` there means
the vault predates the column widening in cashu-vault 0.11.1, which sized `key_set_id` for v1 ids.

**Provisioning is refused for a mint id that already has an active keyset.** This is deliberate.
Provision a new mint id rather than trying to re-provision an existing one.
