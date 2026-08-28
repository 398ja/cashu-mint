# Back up a keyset's private keys

Take a recoverable copy of the private keys behind a mint's denominations, using the
admin only to find out *where* the keys are.

The admin never holds a private key. It records the **vault path** of each
denomination, and reading that path needs HashiCorp Vault credentials the admin does
not have. That split is the point: an Operator can confirm what was provisioned, and
back it up, without the key crossing the admin, an HTTP response, or a browser.

## Before you start

- A signed-in Operator with `mint:lifecycle`.
- HashiCorp Vault credentials, and `vault` on your PATH.
- The mint's UUID.

## 1. Find the denominations

In the web interface open **Mints → the mint → Keysets**, then expand
**Denominations** on the keyset you are backing up. Archived keysets answer too, and
you want them: an archived keyset refuses to sign but redeems indefinitely
([ADR-0004](../../../docs/adr/0004-archived-keysets-must-refuse-to-sign.md)), so a
recovery that restores only the signing keyset cannot honour tokens already in
circulation.

Or over the API:

```bash
curl -s --cookie "$ADMIN_SESSION" \
  "$ADMIN_URL/admin/lifecycle/mints/$MINT_ID/keysets/$KEYSET_ID/denominations"
```

```json
[
  {"amount": 1, "vaultPath": "cashu/keys/021b8c8e-.../00bb04981c77bc64/1"},
  {"amount": 2, "vaultPath": "cashu/keys/021b8c8e-.../00bb04981c77bc64/2"}
]
```

The path is derived — `cashu/keys/{mintId}/{keySetId}/{amount}` — but read it from the
API rather than building it yourself: a rotation that changed the layout would leave a
hand-built path pointing at nothing, and you would not find out until the restore.

## 2. Read the keys out of HashiCorp Vault

```bash
for path in $(curl -s --cookie "$ADMIN_SESSION" \
      "$ADMIN_URL/admin/lifecycle/mints/$MINT_ID/keysets/$KEYSET_ID/denominations" \
      | jq -r '.[].vaultPath'); do
  vault kv get -format=json "$path" > "backup/$(echo "$path" | tr / _).json"
done
```

The output is key material. Store it the way you store key material — encrypted, off
the mint host, access-logged. A backup left in a home directory has moved the secret
out of the vault and defeated the split above.

## 3. Verify the backup covers the keyset

Every denomination the API listed must have a file. A missing one is not a partial
backup — a keyset cannot redeem the amounts it has lost.

```bash
diff \
  <(curl -s --cookie "$ADMIN_SESSION" \
      "$ADMIN_URL/admin/lifecycle/mints/$MINT_ID/keysets/$KEYSET_ID/denominations" \
      | jq -r '.[].vaultPath' | sort) \
  <(ls backup | sed 's/\.json$//; s/_/\//g' | sort)
```

## Repeat after every rotation

A rotation provisions a new signing keyset. Until you have backed it up, the mint can
issue tokens it could not honour after a vault loss. Back up the new keyset as part of
the rotation, not on a schedule.

## What the admin cannot tell you

- **The public keys.** The vault stores none, and deriving them needs the private key.
  The mint advertises them under NUT-01; the admin does not ask the mint over HTTP
  ([ADR-0003](../../../docs/adr/0003-admin-provisions-key-material-in-the-shared-vault.md)).
- **Whether the mint loaded the keyset.** The admin reads the vault, not the mint. Ask
  the mint's own `/v1/keysets` for that.
